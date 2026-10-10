import logging

from monet_api.llm.base import LLMError, LLMProvider

log = logging.getLogger(__name__)


class FallbackProvider:
    """Try providers in order; the first success wins. `model` is the primary (used in cache keys)."""

    def __init__(self, providers: list[LLMProvider]) -> None:
        self.providers = providers
        self.name = providers[0].name
        self.model = providers[0].model

    async def complete_json(self, system: str, user: str) -> dict:
        errors = []
        for p in self.providers:
            try:
                return await p.complete_json(system, user)
            except LLMError as e:
                log.warning("provider %s/%s failed, trying next: %s", p.name, p.model, str(e)[:160])
                errors.append(str(e))
        raise LLMError("all providers failed: " + " | ".join(e[:120] for e in errors))
