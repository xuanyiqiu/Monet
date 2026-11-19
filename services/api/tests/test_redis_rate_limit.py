import uuid

import pytest
import redis

from monet_api.auth import RateLimiter, RedisRateLimiter
from monet_api.errors import Problem
from monet_api.main import build_rate_limiter


class FakeRedis:
    def __init__(self) -> None:
        self.counts: dict[str, int] = {}
        self.expires: list[tuple[str, int]] = []

    def incr(self, key: str, amount: int = 1) -> int:
        self.counts[key] = self.counts.get(key, 0) + amount
        return self.counts[key]

    def expire(self, key: str, seconds: int) -> bool:
        self.expires.append((key, seconds))
        return True


class DownRedis:
    def incr(self, key: str, amount: int = 1) -> int:
        raise ConnectionError("down")

    def expire(self, key: str, seconds: int) -> bool:
        return True


class ExpireFailsRedis(FakeRedis):
    def expire(self, key: str, seconds: int) -> bool:
        raise ConnectionError("down")


def test_third_request_in_window_is_rejected():
    limiter = RedisRateLimiter(FakeRedis(), 2, clock=lambda: 610.0)
    limiter.take("u1")
    limiter.take("u1")
    with pytest.raises(Problem) as exc:
        limiter.take("u1")
    assert exc.value.status == 429
    assert exc.value.headers == {"Retry-After": "50"}


def test_users_are_independent():
    limiter = RedisRateLimiter(FakeRedis(), 1, clock=lambda: 610.0)
    limiter.take("a")
    limiter.take("b")
    with pytest.raises(Problem):
        limiter.take("a")


def test_window_reset():
    now = [610.0]
    limiter = RedisRateLimiter(FakeRedis(), 1, clock=lambda: now[0])
    limiter.take("a")
    with pytest.raises(Problem):
        limiter.take("a")
    now[0] = 660.0
    limiter.take("a")


def test_fails_open_when_redis_is_down():
    limiter = RedisRateLimiter(DownRedis(), 2, clock=lambda: 610.0)
    assert limiter.take("a") is None


def test_expire_once_per_key_on_first_hit():
    client = FakeRedis()
    limiter = RedisRateLimiter(client, 2, clock=lambda: 610.0)
    limiter.take("u1")
    limiter.take("u1")
    assert client.expires == [("monet:rl:u1:10", 120)]


def test_expire_failure_does_not_stop_enforcement():
    limiter = RedisRateLimiter(ExpireFailsRedis(), 2, clock=lambda: 610.0)
    limiter.take("u1")
    limiter.take("u1")
    with pytest.raises(Problem) as exc:
        limiter.take("u1")
    assert exc.value.status == 429


def test_build_rate_limiter_uses_redis_only_when_configured(settings):
    assert isinstance(build_rate_limiter(settings.model_copy(update={"redis_url": None})), RateLimiter)
    assert isinstance(
        build_rate_limiter(settings.model_copy(update={"redis_url": "redis://127.0.0.1:1/0"})),
        RedisRateLimiter,
    )


def test_real_redis_container():
    client = redis.Redis.from_url("redis://127.0.0.1:63790/0")
    try:
        client.ping()
    except Exception:
        pytest.skip("redis container is not running")
    limiter = RedisRateLimiter(client, 2)
    user = uuid.uuid4().hex
    limiter.take(user)
    limiter.take(user)
    with pytest.raises(Problem) as exc:
        limiter.take(user)
    assert exc.value.status == 429
