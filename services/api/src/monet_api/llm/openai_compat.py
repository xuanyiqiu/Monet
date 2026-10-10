import httpx

from monet_api.llm.base import LLMError, parse_json_object


class OpenAICompatProvider:
    """Any /chat/completions API: Groq, DeepSeek, Qwen (DashScope), OpenRouter, Ollama..."""

    name = "openai_compat"

    def __init__(
        self,
        base_url: str,
        api_key: str | None,
        model: str,
        timeout_s: float,
        temperature: float,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.model = model
        self._url = base_url.rstrip("/") + "/chat/completions"
        self._headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._temperature = temperature
        self._client = client or httpx.AsyncClient(timeout=timeout_s)

    async def complete_json(self, system: str, user: str) -> dict:
        body = {
            "model": self.model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": self._temperature,
            "response_format": {"type": "json_object"},
        }
        try:
            r = await self._client.post(self._url, json=body, headers=self._headers)
        except httpx.HTTPError as e:
            raise LLMError(f"llm request failed: {e}") from e
        if r.status_code != 200:
            raise LLMError(f"llm HTTP {r.status_code}: {r.text[:300]}")
        try:
            content = r.json()["choices"][0]["message"]["content"]
        except (KeyError, IndexError, ValueError) as e:
            raise LLMError(f"llm unexpected response: {r.text[:300]}") from e
        return parse_json_object(content)
