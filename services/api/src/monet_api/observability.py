"""Observability: request ids, JSON logs, Prometheus-style metrics, and LLM call instrumentation.

- Every HTTP request gets an id (client `X-Request-ID` if it looks sane, else generated), echoed in the
  response header and attached to every log line written while handling it.
- One access-log line per request with the *route template* (never the raw path: no high-cardinality labels).
- `Metrics` is a tiny in-process registry (counters + histograms) rendered in the Prometheus text format.
"""

import asyncio
import contextvars
import json
import logging
import re
import sys
import threading
import time
import uuid
from collections import defaultdict
from datetime import UTC, datetime

from monet_api.llm.base import LLMError, LLMProvider, LLMRateLimited

request_id_var: contextvars.ContextVar[str | None] = contextvars.ContextVar("request_id", default=None)
access_log = logging.getLogger("monet.access")

_RID_OK = re.compile(r"^[A-Za-z0-9._-]{8,64}$")


# ----------------------------------------------------------------------------- logging


class RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get() or "-"
        return True


_STANDARD = set(logging.LogRecord("", 0, "", 0, "", (), None).__dict__) | {"message", "asctime", "taskName"}


class JsonFormatter(logging.Formatter):
    """One JSON object per line; extra fields passed via `extra={...}` become top-level keys."""

    def format(self, record: logging.LogRecord) -> str:
        out: dict = {
            "ts": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        rid = getattr(record, "request_id", None) or request_id_var.get()
        if rid and rid != "-":
            out["request_id"] = rid
        for key, value in record.__dict__.items():
            if key not in _STANDARD and not key.startswith("_") and key != "request_id":
                out[key] = value
        if record.exc_info:
            out["exc"] = self.formatException(record.exc_info)
        return json.dumps(out, ensure_ascii=False, default=str)


def configure_logging(json_logs: bool, level: int = logging.INFO) -> None:
    """Idempotent: replaces the handler it installed before, leaves other handlers alone."""
    root = logging.getLogger()
    for h in [h for h in root.handlers if getattr(h, "_monet", False)]:
        root.removeHandler(h)
    handler = logging.StreamHandler(sys.stderr)
    handler._monet = True  # type: ignore[attr-defined]
    handler.addFilter(RequestIdFilter())
    handler.setFormatter(
        JsonFormatter()
        if json_logs
        else logging.Formatter("%(asctime)s %(levelname)s [%(request_id)s] %(name)s: %(message)s")
    )
    root.addHandler(handler)
    root.setLevel(level)


# ----------------------------------------------------------------------------- metrics

BUCKETS = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0)

METRICS = {
    "monet_http_requests_total": ("counter", "HTTP requests by method, route template and status."),
    "monet_http_request_duration_seconds": ("histogram", "HTTP request latency by method and route."),
    "monet_llm_calls_total": ("counter", "LLM provider calls by provider, model and outcome."),
    "monet_llm_call_duration_seconds": ("histogram", "LLM provider call latency by provider and model."),
    "monet_llm_hedges_total": ("counter", "Extra LLM attempts started because the current one was slow."),
}


def _esc(v: str) -> str:
    return v.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def _labels(key: tuple) -> str:
    return "{" + ",".join(f'{k}="{_esc(str(v))}"' for k, v in key) + "}" if key else ""


class Metrics:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counters: dict[str, dict[tuple, float]] = defaultdict(dict)
        self._hists: dict[str, dict[tuple, list]] = defaultdict(dict)  # key -> [bucket counts, sum, count]

    def inc(self, name: str, value: float = 1.0, **labels: str) -> None:
        key = tuple(sorted(labels.items()))
        with self._lock:
            self._counters[name][key] = self._counters[name].get(key, 0.0) + value

    def observe(self, name: str, value: float, **labels: str) -> None:
        key = tuple(sorted(labels.items()))
        with self._lock:
            h = self._hists[name].setdefault(key, [[0] * len(BUCKETS), 0.0, 0])
            for i, upper in enumerate(BUCKETS):
                if value <= upper:
                    h[0][i] += 1
            h[1] += value
            h[2] += 1

    def counter(self, name: str, **labels: str) -> float:
        return self._counters[name].get(tuple(sorted(labels.items())), 0.0)

    def render(self) -> str:
        """Prometheus text exposition format."""
        lines: list[str] = []
        with self._lock:
            for name, (kind, help_) in METRICS.items():
                series = self._counters.get(name) if kind == "counter" else self._hists.get(name)
                if not series:
                    continue
                lines += [f"# HELP {name} {help_}", f"# TYPE {name} {kind}"]
                for key, val in sorted(series.items()):
                    if kind == "counter":
                        lines.append(f"{name}{_labels(key)} {val:g}")
                        continue
                    counts, total, n = val
                    for upper, c in zip(BUCKETS, counts, strict=True):
                        lines.append(f"{name}_bucket{_labels((*key, ('le', f'{upper:g}')))} {c}")
                    lines.append(f"{name}_bucket{_labels((*key, ('le', '+Inf')))} {n}")
                    lines.append(f"{name}_sum{_labels(key)} {total:.6f}")
                    lines.append(f"{name}_count{_labels(key)} {n}")
        return "\n".join(lines) + "\n"

    def snapshot(self) -> dict:
        with self._lock:
            return {
                "counters": {
                    n: [{"labels": dict(k), "value": v} for k, v in s.items()]
                    for n, s in self._counters.items()
                },
                "histograms": {
                    n: [{"labels": dict(k), "count": h[2], "sum": round(h[1], 6)} for k, h in s.items()]
                    for n, s in self._hists.items()
                },
            }


# ----------------------------------------------------------------------------- HTTP middleware


class ObservabilityMiddleware:
    """Pure ASGI (no BaseHTTPMiddleware): request id, access log and HTTP metrics."""

    def __init__(self, app, metrics: Metrics) -> None:
        self.app = app
        self.metrics = metrics

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        raw = dict(scope["headers"]).get(b"x-request-id", b"").decode("latin-1")
        rid = raw if _RID_OK.match(raw) else uuid.uuid4().hex
        token = request_id_var.set(rid)
        status = 500
        started = time.perf_counter()

        async def send_with_id(message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                message = {
                    **message,
                    "headers": [*message.get("headers", []), (b"x-request-id", rid.encode())],
                }
            await send(message)

        try:
            await self.app(scope, receive, send_with_id)
        finally:
            duration = time.perf_counter() - started
            route = getattr(scope.get("route"), "path", None) or "unmatched"
            method = scope["method"]
            self.metrics.inc("monet_http_requests_total", method=method, route=route, status=str(status))
            self.metrics.observe("monet_http_request_duration_seconds", duration, method=method, route=route)
            quiet = route in ("/v1/health", "/v1/metrics")
            access_log.log(
                logging.DEBUG if quiet else logging.INFO,
                "%s %s -> %d",
                method,
                route,
                status,
                extra={
                    "method": method,
                    "route": route,
                    "status": status,
                    "duration_ms": round(duration * 1000, 1),
                },
            )
            request_id_var.reset(token)


# ----------------------------------------------------------------------------- LLM instrumentation


class InstrumentedProvider:
    """Wraps an LLM provider and records outcome and latency of every call."""

    def __init__(self, inner: LLMProvider, metrics: Metrics) -> None:
        self.inner = inner
        self.metrics = metrics
        self.name = inner.name
        self.model = inner.model

    async def complete_json(self, system: str, user: str) -> dict:
        started = time.perf_counter()
        outcome = "ok"
        try:
            return await self.inner.complete_json(system, user)
        except LLMRateLimited:
            outcome = "rate_limited"
            raise
        except LLMError:
            outcome = "error"
            raise
        except asyncio.CancelledError:
            outcome = "cancelled"  # e.g. the loser of a hedged request
            raise
        finally:
            labels = {"provider": self.name, "model": self.model}
            self.metrics.inc("monet_llm_calls_total", outcome=outcome, **labels)
            self.metrics.observe("monet_llm_call_duration_seconds", time.perf_counter() - started, **labels)
