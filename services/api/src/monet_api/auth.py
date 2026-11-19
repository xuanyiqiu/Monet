import logging
import math
import re
import time
from collections import defaultdict
from collections.abc import Callable

import jwt
import redis
from fastapi import Depends, Request

from monet_api.config import Settings
from monet_api.errors import Problem

log = logging.getLogger(__name__)
_DEV_TOKEN = re.compile(r"^dev:([A-Za-z0-9_.-]{1,64})$")


def current_user(request: Request) -> str:
    s: Settings = request.app.state.settings
    header = request.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise Problem(401, "Missing bearer token", headers={"WWW-Authenticate": "Bearer"})
    if s.auth_mode == "dev":
        if m := _DEV_TOKEN.match(token):
            return m.group(1)
        raise Problem(401, "Invalid dev token", "Use `Bearer dev:<user-id>` in dev mode.")
    return _verify_supabase(token, s)


_BEARER = {"WWW-Authenticate": "Bearer"}
_jwks_clients: dict[str, jwt.PyJWKClient] = {}


def _jwks_client(supabase_url: str) -> jwt.PyJWKClient:
    """One cached client per project; keys are cached in memory and refreshed on key rotation."""
    url = supabase_url.rstrip("/")
    if url not in _jwks_clients:
        _jwks_clients[url] = jwt.PyJWKClient(
            f"{url}/auth/v1/.well-known/jwks.json", cache_keys=True, lifespan=3600
        )
    return _jwks_clients[url]


def _verify_supabase(token: str, s: Settings) -> str:
    """Verify a Supabase access token: HS256 (shared secret) or ES256/RS256 (project JWKS).

    The algorithm is chosen from an allow-list by the token header, never trusted blindly:
    `none` and anything else is rejected, and the key type must match.
    """
    try:
        alg = jwt.get_unverified_header(token).get("alg")
    except jwt.PyJWTError as e:
        raise Problem(401, "Invalid token", str(e), headers=_BEARER) from e
    options: dict = {"require": ["exp", "sub"]}
    issuer = None
    try:
        if alg == "HS256":
            if not s.supabase_jwt_secret:
                raise Problem(500, "Auth not configured")
            key = s.supabase_jwt_secret
        elif alg in ("ES256", "RS256"):
            if not s.supabase_url:
                raise Problem(500, "Auth not configured")
            key = _jwks_client(s.supabase_url).get_signing_key_from_jwt(token).key
            issuer = f"{s.supabase_url.rstrip('/')}/auth/v1"
        else:
            raise Problem(401, "Invalid token", f"unsupported algorithm {alg!r}", headers=_BEARER)
        claims = jwt.decode(
            token, key, algorithms=[alg], audience=s.supabase_jwt_audience, issuer=issuer, options=options
        )
    except jwt.PyJWTError as e:
        raise Problem(401, "Invalid token", str(e), headers=_BEARER) from e
    return str(claims["sub"])


class RateLimiter:
    """In-memory token bucket per user. Enough for one instance (Cloud Run max-instances is kept low)."""

    def __init__(self, per_minute: int) -> None:
        self.rate = per_minute / 60.0
        self.capacity = float(per_minute)
        self._buckets: dict[str, tuple[float, float]] = defaultdict(lambda: (self.capacity, time.monotonic()))

    def take(self, user: str, cost: float = 1.0) -> None:
        tokens, last = self._buckets[user]
        now = time.monotonic()
        tokens = min(self.capacity, tokens + (now - last) * self.rate)
        if tokens < cost:
            retry = int((cost - tokens) / self.rate) + 1
            raise Problem(429, "Too many requests", f"Retry in {retry}s", headers={"Retry-After": str(retry)})
        self._buckets[user] = (tokens - cost, now)


class RedisRateLimiter:
    """Per-user fixed one-minute window counted in Redis, shared by every instance."""

    def __init__(self, client: redis.Redis, per_minute: int, clock: Callable[[], float] = time.time) -> None:
        self._client = client
        self._per_minute = per_minute
        self._clock = clock

    def take(self, user: str, cost: float = 1.0) -> None:
        now = self._clock()
        window = int(now // 60)
        key = f"monet:rl:{user}:{window}"
        amount = max(1, math.ceil(cost))
        try:
            count = self._client.incr(key, amount)
        except Exception as e:
            log.warning("redis rate limit failed: %s", e)
            return
        if count == amount:
            try:
                self._client.expire(key, 120)
            except Exception as e:
                log.warning("redis rate limit failed: %s", e)
        if count > self._per_minute:
            retry = 60 - int(now % 60)
            raise Problem(429, "Too many requests", f"Retry in {retry}s", headers={"Retry-After": str(retry)})


def rate_limited_user(request: Request, user: str = Depends(current_user)) -> str:
    request.app.state.rate_limiter.take(user)
    return user
