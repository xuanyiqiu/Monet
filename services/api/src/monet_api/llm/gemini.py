import httpx

from monet_api.llm.base import LLMError, parse_json_object
from monet_api.llm.http import post_with_retry

_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


class GeminiProvider:
    name = "gemini"

    def __init__(
        self,
        api_key: str,
        model: str,
        timeout_s: float,
        temperature: float,
        client: httpx.AsyncClient | None = None,
        thinking_budget: int | None = None,
    ) -> None:
        self.model = model
        self._thinking_budget = thinking_budget
        self._key = api_key
        self._temperature = temperature
        self._client = client or httpx.AsyncClient(timeout=timeout_s)

    async def complete_json(self, system: str, user: str) -> dict:
        body = {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": [{"text": user}]}],
            "generationConfig": {"responseMimeType": "application/json", "temperature": self._temperature},
        }
        if self._thinking_budget is not None:
            body["generationConfig"]["thinkingConfig"] = {"thinkingBudget": self._thinking_budget}
        r = await post_with_retry(
            self._client,
            _URL.format(model=self.model),
            json=body,
            headers={"x-goog-api-key": self._key},
            label="gemini",
        )
        try:
            parts = r.json()["candidates"][0]["content"]["parts"]
        except (KeyError, IndexError, ValueError) as e:
            raise LLMError(f"gemini unexpected response: {r.text[:300]}") from e
        return parse_json_object("".join(p.get("text", "") for p in parts))
