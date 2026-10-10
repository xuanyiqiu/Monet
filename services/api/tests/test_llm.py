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
