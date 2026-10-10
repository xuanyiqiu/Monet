"""Two-tier JSON cache: in-process LRU in front of optional Redis."""

import hashlib
import json
import logging
import time
from collections import OrderedDict
from typing import Protocol

log = logging.getLogger(__name__)


def cache_key(*parts: str) -> str:
    return hashlib.sha256("\x1f".join(parts).encode()).hexdigest()[:32]


class Cache(Protocol):
    async def get(self, key: str) -> dict | None: ...
    async def set(self, key: str, value: dict, ttl_s: int = 30 * 86400) -> None: ...


class MemoryCache:
    def __init__(self, max_items: int = 5000) -> None:
        self._data: OrderedDict[str, tuple[float, dict]] = OrderedDict()
        self._max = max_items

    async def get(self, key: str) -> dict | None:
        item = self._data.get(key)
        if item is None:
            return None
        expires, value = item
        if expires < time.monotonic():
            del self._data[key]
            return None
        self._data.move_to_end(key)
        return value

    async def set(self, key: str, value: dict, ttl_s: int = 30 * 86400) -> None:
        self._data[key] = (time.monotonic() + ttl_s, value)
        self._data.move_to_end(key)
        while len(self._data) > self._max:
            self._data.popitem(last=False)


class TieredCache:
    def __init__(self, memory: MemoryCache, redis_url: str | None = None) -> None:
        self._mem = memory
        self._redis = None
        if redis_url:
            import redis.asyncio as redis

            self._redis = redis.from_url(redis_url)

    async def get(self, key: str) -> dict | None:
        if (v := await self._mem.get(key)) is not None:
            return v
        if self._redis is None:
            return None
        try:
            raw = await self._redis.get(f"monet:{key}")
        except Exception as e:  # cache must never break a request
            log.warning("redis get failed: %s", e)
            return None
        if raw is None:
            return None
        v = json.loads(raw)
        await self._mem.set(key, v)
        return v

    async def set(self, key: str, value: dict, ttl_s: int = 30 * 86400) -> None:
        await self._mem.set(key, value, ttl_s)
        if self._redis is not None:
            try:
                await self._redis.set(f"monet:{key}", json.dumps(value, ensure_ascii=False), ex=ttl_s)
            except Exception as e:
                log.warning("redis set failed: %s", e)
