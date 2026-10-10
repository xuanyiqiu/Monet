import asyncio

import httpx
import pytest

from monet_api.llm.base import LLMError, parse_json_object
from monet_api.llm.gemini import GeminiProvider
from monet_api.llm.http import post_with_retry
from monet_api.llm.openai_compat import OpenAICompatProvider


def test_parse_json_object_tolerates_fences_and_chatter():
    assert parse_json_object('```json\n{"a": 1}\n```') == {"a": 1}
    assert parse_json_object('Sure! {"a": {"b": 2}} hope it helps') == {"a": {"b": 2}}
    for bad in ("nope", "[1, 2]", '{"a": '):
        with pytest.raises(LLMError):
            parse_json_object(bad)


def client_with(handler) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_retry_then_success(monkeypatch):
    monkeypatch.setattr("asyncio.sleep", lambda s: _noop())
    calls = []

    def handler(req):
        calls.append(1)
        return httpx.Response(503, text="busy") if len(calls) < 3 else httpx.Response(200, json={"ok": True})

    r = await post_with_retry(client_with(handler), "http://x", json={}, headers={})
    assert r.json() == {"ok": True} and len(calls) == 3


async def test_no_retry_on_client_error():
    calls = []

    def handler(req):
        calls.append(1)
        return httpx.Response(404, text="model gone")

    with pytest.raises(LLMError, match="HTTP 404"):
        await post_with_retry(client_with(handler), "http://x", json={}, headers={})
    assert len(calls) == 1


async def test_transport_error_message_is_not_empty(monkeypatch):
    monkeypatch.setattr("asyncio.sleep", lambda s: _noop())

    def handler(req):
        raise httpx.ReadTimeout("")

    with pytest.raises(LLMError, match="ReadTimeout"):
        await post_with_retry(client_with(handler), "http://x", json={}, headers={}, retries=1)


async def _noop():
    return None


async def test_gemini_parses_candidates_and_sends_key_header():
    seen = {}

    def handler(req):
        seen["key"] = req.headers["x-goog-api-key"]
        body = {"candidates": [{"content": {"parts": [{"text": '{"translation": "hi"}'}]}}]}
        return httpx.Response(200, json=body)

    p = GeminiProvider("K", "m", 5, 0.2, client=client_with(handler))
    assert await p.complete_json("sys", "{}") == {"translation": "hi"}
    assert seen["key"] == "K"


async def test_openai_compat_parses_choices():
    def handler(req):
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"x": 1}'}}]})

    p = OpenAICompatProvider("http://h/v1/", "k", "m", 5, 0.2, client=client_with(handler))
    assert await p.complete_json("s", "{}") == {"x": 1}


async def test_fallback_uses_next_provider_and_reports_all_failures():
    from monet_api.llm.fake import FakeProvider
    from monet_api.llm.fallback import FallbackProvider

    ok = FakeProvider()
    p = FallbackProvider([FakeProvider(fail=True), ok])
    out = await p.complete_json("s", '{"task": "annotate", "target_lang": "en", "text": "x", "tokens": []}')
    assert out["translation"] == "[en] x" and ok.calls == 1
    with pytest.raises(LLMError, match="all providers failed"):
        await FallbackProvider([FakeProvider(fail=True), FakeProvider(fail=True)]).complete_json("s", "{}")


async def test_429_is_not_retried_and_carries_retry_delay():
    from monet_api.llm.base import LLMRateLimited

    calls = []

    def handler(req):
        calls.append(1)
        return httpx.Response(429, text='{"error": {"details": [{"retryDelay": "17s"}]}}')

    with pytest.raises(LLMRateLimited) as ei:
        await post_with_retry(client_with(handler), "http://x", json={}, headers={})
    assert len(calls) == 1 and ei.value.retry_after == 17.0


async def test_429_honors_retry_after_header_and_has_a_default():
    from monet_api.llm.base import LLMRateLimited

    def with_header(req):
        return httpx.Response(429, headers={"retry-after": "5"}, text="slow down")

    def bare(req):
        return httpx.Response(429, text="quota")

    with pytest.raises(LLMRateLimited) as a:
        await post_with_retry(client_with(with_header), "http://x", json={}, headers={})
    with pytest.raises(LLMRateLimited) as b:
        await post_with_retry(client_with(bare), "http://x", json={}, headers={})
    assert (a.value.retry_after, b.value.retry_after) == (5.0, 30.0)


class _Stub:
    def __init__(self, model, behavior):
        self.name, self.model, self.behavior, self.calls = "stub", model, behavior, 0

    async def complete_json(self, system, user):
        self.calls += 1
        if isinstance(self.behavior, Exception):
            raise self.behavior
        return self.behavior


async def test_rate_limited_provider_is_skipped_until_cooldown_ends():
    from monet_api.llm.base import LLMRateLimited
    from monet_api.llm.fallback import FallbackProvider

    now = [0.0]
    primary = _Stub("a", LLMRateLimited("quota", retry_after=30))
    backup = _Stub("b", {"ok": True})
    chain = FallbackProvider([primary, backup], clock=lambda: now[0])

    assert await chain.complete_json("s", "{}") == {"ok": True}
    assert (primary.calls, backup.calls) == (1, 1)
    for _ in range(5):  # within cooldown: straight to the backup, the saturated model is not hit
        await chain.complete_json("s", "{}")
    assert (primary.calls, backup.calls) == (1, 6)

    now[0] = 31.0  # cooldown over: primary is tried first again
    primary.behavior = {"ok": "primary"}
    assert await chain.complete_json("s", "{}") == {"ok": "primary"}
    assert primary.calls == 2


async def test_all_providers_cooling_down_still_tries_them_soonest_first():
    from monet_api.llm.base import LLMRateLimited
    from monet_api.llm.fallback import FallbackProvider

    now = [0.0]
    a = _Stub("a", LLMRateLimited("q", retry_after=60))
    b = _Stub("b", LLMRateLimited("q", retry_after=10))
    chain = FallbackProvider([a, b], clock=lambda: now[0])
    with pytest.raises(LLMError, match="all providers failed"):
        await chain.complete_json("s", "{}")
    b.behavior = {"ok": "b recovered"}
    now[0] = 1.0
    assert await chain.complete_json("s", "{}") == {"ok": "b recovered"}  # b expires first, so it goes first


class _Timed:
    def __init__(self, model, delay=0.0, result=None, error=None):
        self.name, self.model = "timed", model
        self.delay, self.result, self.error = delay, result, error
        self.started = self.finished = self.cancelled = 0

    async def complete_json(self, system, user):
        self.started += 1
        try:
            await asyncio.sleep(self.delay)
        except asyncio.CancelledError:
            self.cancelled += 1
            raise
        self.finished += 1
        if self.error:
            raise self.error
        return self.result


async def test_hedging_returns_the_backup_when_primary_is_slow():
    import time

    from monet_api.llm.fallback import FallbackProvider

    slow = _Timed("slow", delay=5.0, result={"who": "slow"})
    fast = _Timed("fast", delay=0.01, result={"who": "fast"})
    chain = FallbackProvider([slow, fast], hedge_after_s=0.05)
    t = time.monotonic()
    assert await chain.complete_json("s", "{}") == {"who": "fast"}
    assert time.monotonic() - t < 1.0
    assert (slow.started, slow.cancelled, slow.finished) == (1, 1, 0)  # the loser is cancelled, not leaked


async def test_no_hedge_when_primary_answers_in_time():
    from monet_api.llm.fallback import FallbackProvider

    primary = _Timed("p", delay=0.01, result={"who": "p"})
    backup = _Timed("b", delay=0.01, result={"who": "b"})
    assert await FallbackProvider([primary, backup], hedge_after_s=1.0).complete_json("s", "{}") == {
        "who": "p"
    }
    assert backup.started == 0


async def test_error_fails_over_immediately_without_waiting_for_hedge_timer():
    import time

    from monet_api.llm.fallback import FallbackProvider

    bad = _Timed("bad", delay=0.0, error=LLMError("boom"))
    good = _Timed("good", delay=0.0, result={"ok": 1})
    t = time.monotonic()
    assert await FallbackProvider([bad, good], hedge_after_s=30.0).complete_json("s", "{}") == {"ok": 1}
    assert time.monotonic() - t < 1.0


async def test_hedged_primary_can_still_win_and_errors_are_aggregated():
    from monet_api.llm.fallback import FallbackProvider

    primary = _Timed("p", delay=0.15, result={"who": "p"})
    backup = _Timed("b", delay=5.0, result={"who": "b"})
    assert await FallbackProvider([primary, backup], hedge_after_s=0.05).complete_json("s", "{}") == {
        "who": "p"
    }
    assert backup.cancelled == 1

    a = _Timed("a", error=LLMError("first"))
    b = _Timed("b", error=LLMError("second"))
    with pytest.raises(LLMError, match="first.*second"):
        await FallbackProvider([a, b], hedge_after_s=0.05).complete_json("s", "{}")


class _SometimesSlow:
    """First call hangs, later calls answer fast: a model with an independent latency spike."""

    name, model = "flaky-speed", "m"

    def __init__(self):
        self.calls = 0

    async def complete_json(self, system, user):
        self.calls += 1
        await asyncio.sleep(5.0 if self.calls == 1 else 0.01)
        return {"call": self.calls}


async def test_single_provider_gets_one_duplicate_request_when_slow():
    import time

    from monet_api.llm.fallback import FallbackProvider

    p = _SometimesSlow()
    t = time.monotonic()
    out = await FallbackProvider([p], hedge_after_s=0.05).complete_json("s", "{}")
    assert out == {"call": 2} and time.monotonic() - t < 1.0 and p.calls == 2


async def test_duplicate_is_sent_at_most_once():
    from monet_api.llm.fallback import FallbackProvider

    always_slow = _Timed("s", delay=0.4, result={"ok": 1})
    out = await FallbackProvider([always_slow], hedge_after_s=0.05).complete_json("s", "{}")
    assert out == {"ok": 1} and always_slow.started == 2
