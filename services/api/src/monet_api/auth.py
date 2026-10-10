import re
import time
from collections import defaultdict

import jwt
from fastapi import Depends, Request

from monet_api.config import Settings
from monet_api.errors import Problem

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
    if not s.supabase_jwt_secret:
        raise Problem(500, "Auth not configured")
    try:
        claims = jwt.decode(
            token, s.supabase_jwt_secret, algorithms=["HS256"], audience=s.supabase_jwt_audience
        )
    except jwt.PyJWTError as e:
        raise Problem(401, "Invalid token", str(e), headers={"WWW-Authenticate": "Bearer"}) from e
    sub = claims.get("sub")
    if not sub:
        raise Problem(401, "Invalid token", "missing sub")
    return str(sub)


class RateLimiter:
    """In-memory token bucket per user. Good enough for one instance; Redis-backed version is A-08."""

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


def rate_limited_user(request: Request, user: str = Depends(current_user)) -> str:
    request.app.state.rate_limiter.take(user)
    return user
