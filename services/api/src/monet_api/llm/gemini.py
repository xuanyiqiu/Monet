import httpx

from monet_api.llm.base import LLMError, parse_json_object

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
    ) -> None:
        self.model = model
        self._key = api_key
        self._temperature = temperature
        self._client = client or httpx.AsyncClient(timeout=timeout_s)

    async def complete_json(self, system: str, user: str) -> dict:
        body = {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": [{"text": user}]}],
            "generationConfig": {"responseMimeType": "application/json", "temperature": self._temperature},
        }
        try:
            r = await self._client.post(
                _URL.format(model=self.model), json=body, headers={"x-goog-api-key": self._key}
            )
        except httpx.HTTPError as e:
            raise LLMError(f"gemini request failed: {e}") from e
        if r.status_code != 200:
            raise LLMError(f"gemini HTTP {r.status_code}: {r.text[:300]}")
        try:
            parts = r.json()["candidates"][0]["content"]["parts"]
        except (KeyError, IndexError, ValueError) as e:
            raise LLMError(f"gemini unexpected response: {r.text[:300]}") from e
        return parse_json_object("".join(p.get("text", "") for p in parts))
