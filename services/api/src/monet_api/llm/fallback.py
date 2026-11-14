import asyncio
import logging
import time
from collections.abc import Callable

from monet_api.llm.base import LLMError, LLMProvider, LLMRateLimited

log = logging.getLogger(__name__)


class FallbackProvider:
    """Try providers in priority order; the first success wins. `model` is the primary (used in cache keys).

    - Failover: an error moves on to the next provider immediately.
    - Cooldown: a provider that reports a rate limit is skipped until its `retry_after` passes, so a
      saturated free-tier model costs one failed call per cooldown instead of one per request. Cooled-down
      providers are still tried last, in case their quota recovered early.
    - Hedging: if the current attempt is still running after `hedge_after_s`, the next provider is started
      in parallel and whichever answers first wins (the other is cancelled). This caps the tail latency that
      free LLM tiers show (a call that normally takes 1 s occasionally takes 15-20 s). When no other provider
      is available, one duplicate request goes to the best provider: slow calls are mostly independent, so the
      second usually answers quickly.
    """

    def __init__(
        self,
        providers: list[LLMProvider],
        hedge_after_s: float | None = 5.0,
        clock=time.monotonic,
        on_hedge: Callable[[str], None] | None = None,
    ) -> None:
        self.on_hedge = on_hedge  # called with "next" or "duplicate" whenever a hedge attempt starts
        self.providers = providers
        self.name = providers[0].name
        self.model = providers[0].model
        self.hedge_after_s = hedge_after_s
        self._clock = clock
        self._cooldown: dict[int, float] = {}

    def _order(self) -> list[LLMProvider]:
        now = self._clock()
        ready = [p for p in self.providers if self._cooldown.get(id(p), 0) <= now]
        cooling = sorted((p for p in self.providers if p not in ready), key=lambda p: self._cooldown[id(p)])
        return ready + cooling

    async def complete_json(self, system: str, user: str) -> dict:
        order = self._order()
        errors: list[str] = []
        owner: dict[asyncio.Task, LLMProvider] = {}
        pending: set[asyncio.Task] = set()
        nxt = 0
        dup_left = 1 if self._cooldown.get(id(order[0]), 0) <= self._clock() else 0

        def launch(provider: LLMProvider | None = None) -> None:
            nonlocal nxt
            if provider is None:
                provider = order[nxt]
                nxt += 1
            task = asyncio.create_task(provider.complete_json(system, user))
            owner[task] = provider
            pending.add(task)

        launch()
        try:
            while pending:
                can_hedge = self.hedge_after_s is not None and (nxt < len(order) or dup_left > 0)
                done, _ = await asyncio.wait(
                    pending,
                    timeout=self.hedge_after_s if can_hedge else None,
                    return_when=asyncio.FIRST_COMPLETED,
                )
                if not done:  # still running after hedge_after_s: start another attempt alongside
                    if nxt < len(order):
                        log.info(
                            "hedging: %s is slow, also trying %s", order[nxt - 1].model, order[nxt].model
                        )
                        launch()
                        if self.on_hedge:
                            self.on_hedge("next")
                    else:
                        if self.on_hedge:
                            self.on_hedge("duplicate")
                        dup_left -= 1
                        log.info("hedging: %s is slow, sending a duplicate request", order[0].model)
                        launch(order[0])
                    continue
                for task in done:
                    pending.discard(task)
                    provider = owner[task]
                    try:
                        return task.result()
                    except LLMRateLimited as e:
                        self._cooldown[id(provider)] = self._clock() + e.retry_after
                        log.warning(
                            "provider %s/%s rate limited for %.0fs",
                            provider.name,
                            provider.model,
                            e.retry_after,
                        )
                        errors.append(str(e))
                    except LLMError as e:
                        log.warning(
                            "provider %s/%s failed, trying next: %s",
                            provider.name,
                            provider.model,
                            str(e)[:160],
                        )
                        errors.append(str(e))
                if not pending and nxt < len(order):
                    launch()
            raise LLMError("all providers failed: " + " | ".join(e[:120] for e in errors))
        finally:
            for task in pending:
                task.cancel()
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)
