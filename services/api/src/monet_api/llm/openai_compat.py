import httpx

from monet_api.llm.base import LLMError, parse_json_object
from monet_api.llm.http import post_with_retry


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
        r = await post_with_retry(self._client, self._url, json=body, headers=self._headers, label="llm")
        try:
            content = r.json()["choices"][0]["message"]["content"]
        except (KeyError, IndexError, ValueError) as e:
            raise LLMError(f"llm unexpected response: {r.text[:300]}") from e
        return parse_json_object(content)
