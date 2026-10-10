from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

API_ROOT = Path(__file__).resolve().parents[2]  # services/api
REPO_ROOT = API_ROOT.parents[1] if len(API_ROOT.parents) > 1 else API_ROOT  # in the image API_ROOT is /app


class Settings(BaseSettings):
    """All runtime configuration. Env vars are prefixed with MONET_ (e.g. MONET_LLM_PROVIDER)."""

    model_config = SettingsConfigDict(
        env_prefix="MONET_", env_file=(REPO_ROOT / ".env", API_ROOT / ".env"), extra="ignore"
    )

    env: Literal["dev", "test", "prod"] = "dev"

    # Auth: "dev" accepts `Bearer dev:<user-id>`; "supabase" verifies Supabase JWTs.
    auth_mode: Literal["dev", "supabase"] = "dev"
    supabase_jwt_secret: str | None = None  # HS256 projects
    supabase_url: str | None = None  # https://<ref>.supabase.co ; enables ES256/RS256 via JWKS
    supabase_jwt_audience: str = "authenticated"

    # LLM
    llm_provider: Literal["fake", "gemini", "openai_compat"] = "fake"
    llm_model: str = "gemini-3.5-flash"
    llm_fallback_models: list[str] = Field(
        default_factory=lambda: ["gemini-3.1-flash-lite", "gemini-3.8-flash"]
    )
    llm_api_key: str | None = None
    llm_base_url: str | None = None  # openai_compat only, e.g. https://api.groq.com/openai/v1
    llm_timeout_s: float = 30.0
    llm_hedge_after_s: float | None = 5.0  # start the next model in parallel if the current one is this slow
    llm_temperature: float = 0.2
    llm_thinking_budget: int | None = 0  # gemini: 0 = no hidden reasoning (much faster); None = model default

    # Storage
    database_url: str | None = None  # postgresql+asyncpg://monet:monet@127.0.0.1:54329/monet
    redis_url: str | None = None
    blob_dir: Path = REPO_ROOT / ".data" / "blobs"
    cache_dir: Path = REPO_ROOT / ".data" / "cache"

    # Data
    lexicon_dir: Path = API_ROOT / "data" / "lexicon"
    chengyu_path: Path = API_ROOT / "data" / "generated" / "chengyu.json"

    # Limits
    rate_limit_per_minute: int = 60
    max_upload_mb: int = 25
    max_pages: int = 500
    lines_per_llm_call: int = 20

    cors_origins: list[str] = Field(default_factory=list)

    @property
    def is_dev(self) -> bool:
        return self.env in ("dev", "test")


@lru_cache
def get_settings() -> Settings:
    return Settings()
