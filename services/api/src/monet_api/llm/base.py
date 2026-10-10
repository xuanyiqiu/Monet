import json
import re
from typing import Protocol


class LLMError(Exception):
    """Provider failed (network, quota, invalid output). Callers degrade gracefully."""


class LLMRateLimited(LLMError):
    """Quota or rate limit hit (HTTP 429). Retrying the same model right away is pointless."""

    def __init__(self, message: str, retry_after: float = 30.0) -> None:
        super().__init__(message)
        self.retry_after = retry_after


class LLMProvider(Protocol):
    name: str
    model: str

    async def complete_json(self, system: str, user: str) -> dict:
        """Return the model's JSON object answer for a system prompt + JSON user payload."""
        ...


_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.MULTILINE)


def parse_json_object(raw: str) -> dict:
    """Parse a model reply that should be a JSON object, tolerating markdown fences."""
    text = _FENCE.sub("", raw.strip())
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        start, end = text.find("{"), text.rfind("}")
        if start < 0 or end <= start:
            raise LLMError("model reply is not JSON") from None
        try:
            data = json.loads(text[start : end + 1])
        except json.JSONDecodeError as e:
            raise LLMError(f"model reply is not JSON: {e}") from e
    if not isinstance(data, dict):
        raise LLMError("model reply is not a JSON object")
    return data
