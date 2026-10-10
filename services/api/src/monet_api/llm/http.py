"""POST with retries for LLM providers: transient 429/5xx and network errors back off and retry."""

import asyncio
import logging

import httpx

from monet_api.llm.base import LLMError

log = logging.getLogger(__name__)
RETRY_STATUS = {408, 429, 500, 502, 503, 504}


async def post_with_retry(
    client: httpx.AsyncClient,
    url: str,
    *,
    json: dict,
    headers: dict[str, str],
    retries: int = 2,
    backoff_s: float = 0.8,
    label: str = "llm",
) -> httpx.Response:
    last = ""
    for attempt in range(retries + 1):
        try:
            r = await client.post(url, json=json, headers=headers)
        except httpx.HTTPError as e:
            last = f"{type(e).__name__}: {e}".rstrip(": ")
        else:
            if r.status_code == 200:
                return r
            last = f"HTTP {r.status_code}: {r.text[:300]}"
            if r.status_code not in RETRY_STATUS:
                raise LLMError(f"{label} {last}")
        if attempt < retries:
            await asyncio.sleep(backoff_s * (2**attempt))
    raise LLMError(f"{label} failed after {retries + 1} attempts: {last}")
