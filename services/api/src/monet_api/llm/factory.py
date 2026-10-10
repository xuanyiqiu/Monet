from monet_api.config import Settings
from monet_api.llm.base import LLMProvider
from monet_api.llm.fake import FakeProvider
from monet_api.llm.fallback import FallbackProvider
from monet_api.llm.gemini import GeminiProvider
from monet_api.llm.openai_compat import OpenAICompatProvider


def build_provider(s: Settings) -> LLMProvider:
    if s.llm_provider == "gemini":
        if not s.llm_api_key:
            raise ValueError("MONET_LLM_API_KEY is required for the gemini provider")
        models = [s.llm_model, *[m for m in s.llm_fallback_models if m != s.llm_model]]
        providers = [
            GeminiProvider(
                s.llm_api_key, m, s.llm_timeout_s, s.llm_temperature, thinking_budget=s.llm_thinking_budget
            )
            for m in models
        ]
        return FallbackProvider(providers) if len(providers) > 1 else providers[0]
    if s.llm_provider == "openai_compat":
        if not s.llm_base_url:
            raise ValueError("MONET_LLM_BASE_URL is required for the openai_compat provider")
        return OpenAICompatProvider(
            s.llm_base_url, s.llm_api_key, s.llm_model, s.llm_timeout_s, s.llm_temperature
        )
    return FakeProvider()
