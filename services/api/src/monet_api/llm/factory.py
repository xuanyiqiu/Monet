from monet_api.config import Settings
from monet_api.llm.base import LLMProvider
from monet_api.llm.fake import FakeProvider
from monet_api.llm.fallback import FallbackProvider
from monet_api.llm.gemini import GeminiProvider
from monet_api.llm.openai_compat import OpenAICompatProvider
from monet_api.observability import InstrumentedProvider, Metrics


def build_provider(s: Settings, metrics: Metrics | None = None) -> LLMProvider:
    """Build the configured provider. With `metrics`, every leaf model records its own call outcomes."""

    def leaf(p: LLMProvider) -> LLMProvider:
        return InstrumentedProvider(p, metrics) if metrics else p

    if s.llm_provider == "gemini":
        if not s.llm_api_key:
            raise ValueError("MONET_LLM_API_KEY is required for the gemini provider")
        models = [s.llm_model, *[m for m in s.llm_fallback_models if m != s.llm_model]]
        providers = [
            leaf(
                GeminiProvider(
                    s.llm_api_key,
                    m,
                    s.llm_timeout_s,
                    s.llm_temperature,
                    thinking_budget=s.llm_thinking_budget,
                )
            )
            for m in models
        ]
        if len(providers) == 1:
            return providers[0]

        def on_hedge(kind: str) -> None:
            if metrics:
                metrics.inc("monet_llm_hedges_total", kind=kind)

        return FallbackProvider(providers, hedge_after_s=s.llm_hedge_after_s, on_hedge=on_hedge)
    if s.llm_provider == "openai_compat":
        if not s.llm_base_url:
            raise ValueError("MONET_LLM_BASE_URL is required for the openai_compat provider")
        return leaf(
            OpenAICompatProvider(
                s.llm_base_url, s.llm_api_key, s.llm_model, s.llm_timeout_s, s.llm_temperature
            )
        )
    return leaf(FakeProvider())
