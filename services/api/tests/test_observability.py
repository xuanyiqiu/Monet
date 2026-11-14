import asyncio
import json
import logging

import pytest
from fastapi.testclient import TestClient

from monet_api.llm.base import LLMError, LLMRateLimited
from monet_api.llm.fake import FakeProvider
from monet_api.llm.fallback import FallbackProvider
from monet_api.main import create_app
from monet_api.observability import (
    InstrumentedProvider,
    JsonFormatter,
    Metrics,
    configure_logging,
    request_id_var,
)
from tests.conftest import AUTH


def metrics_text(client) -> str:
    return client.get("/v1/metrics").text


# ----------------------------------------------------------------------------- request ids


def test_request_id_is_generated_and_returned(client):
    r = client.get("/v1/health")
    rid = r.headers["x-request-id"]
    assert len(rid) == 32 and rid.isalnum()
    assert client.get("/v1/health").headers["x-request-id"] != rid


def test_valid_client_request_id_is_echoed_and_unsafe_one_replaced(client):
    assert (
        client.get("/v1/health", headers={"X-Request-ID": "trace-abc-12345"}).headers["x-request-id"]
        == "trace-abc-12345"
    )
    for bad in ("short", "has spaces in it!!", "x" * 200, "semi;colon;12345", "a/b/c/d/e/f/g/h"):
        got = client.get("/v1/health", headers={"X-Request-ID": bad}).headers["x-request-id"]
        assert got != bad and len(got) == 32


def test_error_responses_carry_request_id(client):
    r = client.post("/v1/annotate", json={"text": "你好", "target_lang": "en"})  # no auth -> 401 problem+json
    assert r.status_code == 401 and "x-request-id" in r.headers


def test_access_log_has_request_id_route_template_and_duration(client, caplog):
    caplog.set_level(logging.INFO, logger="monet.access")
    client.post(
        "/v1/annotate",
        headers={**AUTH, "X-Request-ID": "req-annotate-001"},
        json={"text": "绝绝子", "target_lang": "en"},
    )
    rec = next(r for r in caplog.records if r.name == "monet.access" and r.route == "/v1/annotate")
    assert (rec.method, rec.status) == ("POST", 200) and rec.duration_ms >= 0


def test_logs_written_during_a_request_carry_its_id(client, caplog):
    seen: list[str | None] = []

    class Spy(FakeProvider):
        async def complete_json(self, system, user):
            seen.append(request_id_var.get())
            return await super().complete_json(system, user)

    c = TestClient(create_app(client.app.state.settings, llm=Spy()))
    c.post(
        "/v1/annotate",
        headers={**AUTH, "X-Request-ID": "req-contextvar-1"},
        json={"text": "绝绝子", "target_lang": "en"},
    )
    assert seen == ["req-contextvar-1"]
    assert request_id_var.get() is None  # reset after the request


# ----------------------------------------------------------------------------- JSON logs


def test_json_formatter_outputs_one_json_object_with_extras_and_request_id():
    rec = logging.LogRecord("monet.test", logging.WARNING, __file__, 1, "hello %s", ("世界",), None)
    rec.route = "/v1/x"
    token = request_id_var.set("rid-json-12345")
    try:
        out = json.loads(JsonFormatter().format(rec))
    finally:
        request_id_var.reset(token)
    assert out["msg"] == "hello 世界" and out["level"] == "WARNING" and out["logger"] == "monet.test"
    assert out["route"] == "/v1/x" and out["request_id"] == "rid-json-12345" and out["ts"].endswith("+00:00")


def test_json_formatter_includes_exceptions():
    try:
        raise ValueError("boom")
    except ValueError:
        import sys

        rec = logging.LogRecord("x", logging.ERROR, __file__, 1, "failed", (), sys.exc_info())
    assert "ValueError: boom" in json.loads(JsonFormatter().format(rec))["exc"]


def test_configure_logging_is_idempotent():
    root = logging.getLogger()
    before = len(root.handlers)
    try:
        configure_logging(json_logs=True)
        configure_logging(json_logs=False)
        assert len([h for h in root.handlers if getattr(h, "_monet", False)]) == 1
    finally:
        for h in [h for h in root.handlers if getattr(h, "_monet", False)]:
            root.removeHandler(h)
    assert len(root.handlers) == before


# ----------------------------------------------------------------------------- metrics registry


def test_metrics_render_prometheus_format_with_escaping():
    m = Metrics()
    m.inc("monet_http_requests_total", method="GET", route='/a"b\\c', status="200")
    m.observe("monet_http_request_duration_seconds", 0.03, method="GET", route="/a")
    m.observe("monet_http_request_duration_seconds", 7.0, method="GET", route="/a")
    text = m.render()
    assert "# TYPE monet_http_requests_total counter" in text
    assert 'monet_http_requests_total{method="GET",route="/a\\"b\\\\c",status="200"} 1' in text
    assert 'monet_http_request_duration_seconds_bucket{method="GET",route="/a",le="0.05"} 1' in text
    assert 'monet_http_request_duration_seconds_bucket{method="GET",route="/a",le="10"} 2' in text
    assert 'monet_http_request_duration_seconds_bucket{method="GET",route="/a",le="+Inf"} 2' in text
    assert 'monet_http_request_duration_seconds_count{method="GET",route="/a"} 2' in text
    assert 'monet_http_request_duration_seconds_sum{method="GET",route="/a"} 7.030000' in text


def test_empty_registry_renders_nothing_and_snapshot_is_json_safe():
    m = Metrics()
    assert m.render().strip() == ""
    m.inc("monet_llm_hedges_total", kind="next")
    assert json.loads(json.dumps(m.snapshot()))["counters"]["monet_llm_hedges_total"][0]["value"] == 1


# ----------------------------------------------------------------------------- /v1/metrics end to end


def test_http_metrics_use_route_templates_and_record_errors(client):
    client.post("/v1/annotate", headers=AUTH, json={"text": "绝绝子", "target_lang": "en"})
    client.post("/v1/annotate", headers=AUTH, json={"text": "", "target_lang": "en"})  # 422
    client.get("/v1/documents/00000000-0000-0000-0000-000000000000", headers=AUTH)  # 404 under a template
    client.get("/no/such/path")
    text = metrics_text(client)
    assert 'monet_http_requests_total{method="POST",route="/v1/annotate",status="200"} 1' in text
    assert 'monet_http_requests_total{method="POST",route="/v1/annotate",status="422"} 1' in text
    assert 'route="/v1/documents/{document_id}",status="404"' in text  # template, not the raw id
    assert 'route="unmatched",status="404"' in text
    assert "00000000-0000" not in text


def test_metrics_json_view(client):
    client.get("/v1/health")
    data = client.get("/v1/metrics?format=json").json()
    assert any(s["labels"]["route"] == "/v1/health" for s in data["counters"]["monet_http_requests_total"])


def test_metrics_endpoint_is_not_in_the_api_contract(client):
    assert "/v1/metrics" not in client.app.openapi()["paths"]


def prod_client(settings, fake_llm, token):
    s = settings.model_copy(
        update={
            "env": "prod",
            "auth_mode": "supabase",
            "supabase_jwt_secret": "x" * 48,
            "metrics_token": token,
        }
    )
    return TestClient(create_app(s, llm=fake_llm))


def test_metrics_are_hidden_in_prod_without_the_right_token(settings, fake_llm):
    c = prod_client(settings, fake_llm, "s3cret-token")
    assert c.get("/v1/metrics").status_code == 404
    assert c.get("/v1/metrics", headers={"Authorization": "Bearer wrong"}).status_code == 404
    ok = c.get("/v1/metrics", headers={"Authorization": "Bearer s3cret-token"})
    assert ok.status_code == 200 and ok.headers["content-type"].startswith("text/plain")


def test_metrics_stay_disabled_in_prod_when_no_token_is_configured(settings, fake_llm):
    c = prod_client(settings, fake_llm, None)
    assert c.get("/v1/metrics", headers={"Authorization": "Bearer "}).status_code == 404
    assert c.get("/v1/metrics", headers={"Authorization": "Bearer anything"}).status_code == 404


# ----------------------------------------------------------------------------- LLM metrics


def test_llm_success_and_failure_are_counted_per_provider(settings):
    ok = TestClient(create_app(settings, llm=FakeProvider()))
    ok.post("/v1/annotate", headers=AUTH, json={"text": "绝绝子", "target_lang": "en"})
    text = metrics_text(ok)
    assert 'monet_llm_calls_total{model="fake-1",outcome="ok",provider="fake"} 1' in text
    assert "monet_llm_call_duration_seconds_count" in text

    bad = TestClient(create_app(settings, llm=FakeProvider(fail=True)))
    r = bad.post("/v1/annotate", headers=AUTH, json={"text": "绝绝子", "target_lang": "en"})
    assert r.status_code == 200 and r.json()["translation"] == ""  # degraded, still served
    assert 'outcome="error",provider="fake"} 1' in metrics_text(bad)


class _Raises:
    name, model = "stub", "m"

    def __init__(self, exc):
        self.exc = exc

    async def complete_json(self, system, user):
        raise self.exc


async def test_outcomes_rate_limited_error_and_cancelled():
    m = Metrics()
    with pytest.raises(LLMRateLimited):
        await InstrumentedProvider(_Raises(LLMRateLimited("q", 5)), m).complete_json("s", "{}")
    with pytest.raises(LLMError):
        await InstrumentedProvider(_Raises(LLMError("x")), m).complete_json("s", "{}")
    with pytest.raises(asyncio.CancelledError):
        await InstrumentedProvider(_Raises(asyncio.CancelledError()), m).complete_json("s", "{}")
    for outcome in ("rate_limited", "error", "cancelled"):
        assert m.counter("monet_llm_calls_total", provider="stub", model="m", outcome=outcome) == 1


async def test_hedges_are_reported_through_the_callback():
    class Slow:
        name, model = "slow", "slow"

        async def complete_json(self, system, user):
            await asyncio.sleep(5)

    class Fast:
        name, model = "fast", "fast"

        async def complete_json(self, system, user):
            return {"ok": 1}

    kinds: list[str] = []
    await FallbackProvider([Slow(), Fast()], hedge_after_s=0.02, on_hedge=kinds.append).complete_json(
        "s", "{}"
    )
    assert kinds == ["next"]

    kinds.clear()

    class FlakySpeed:
        name, model, calls = "f", "f", 0

        async def complete_json(self, system, user):
            self.calls += 1
            await asyncio.sleep(5 if self.calls == 1 else 0.001)
            return {"ok": 1}

    await FallbackProvider([FlakySpeed()], hedge_after_s=0.02, on_hedge=kinds.append).complete_json("s", "{}")
    assert kinds == ["duplicate"]
