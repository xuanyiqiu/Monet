import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from monet_api import __version__, errors
from monet_api.annotator import Annotator
from monet_api.auth import RateLimiter
from monet_api.cache import MemoryCache, TieredCache
from monet_api.config import Settings, get_settings
from monet_api.llm import LLMProvider, build_provider
from monet_api.nlp.lexicon import Lexicon, load_lexicon
from monet_api.nlp.tokenize import Tokenizer
from monet_api.routers import core

log = logging.getLogger("monet")

_shared: dict[str, object] = {}


def shared_nlp(settings: Settings) -> tuple[Lexicon, Tokenizer]:
    """Lexicon + jieba take ~1-2 s to build; share them across app instances (tests create many)."""
    key = f"{settings.lexicon_dir}|{settings.chengyu_path}"
    if key not in _shared:
        lexicon = load_lexicon(settings.lexicon_dir, settings.chengyu_path)
        _shared[key] = (lexicon, Tokenizer(lexicon.words(), cache_dir=settings.cache_dir))
    return _shared[key]  # type: ignore[return-value]


def create_app(settings: Settings | None = None, llm: LLMProvider | None = None) -> FastAPI:
    settings = settings or get_settings()
    app = FastAPI(
        title="Monet API",
        version=__version__,
        docs_url="/docs" if settings.is_dev else None,
        redoc_url=None,
    )
    lexicon, tokenizer = shared_nlp(settings)
    app.state.settings = settings
    app.state.annotator = Annotator(
        lexicon,
        tokenizer,
        llm or build_provider(settings),
        TieredCache(MemoryCache(), settings.redis_url),
    )
    app.state.rate_limiter = RateLimiter(settings.rate_limit_per_minute)

    errors.install(app, expose_errors=settings.is_dev)
    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware, allow_origins=settings.cors_origins, allow_methods=["*"], allow_headers=["*"]
        )
    app.include_router(core.router)
    return app


def run() -> None:
    import uvicorn

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    uvicorn.run(create_app(), host="0.0.0.0", port=8000)  # noqa: S104 (emulator reaches host via 10.0.2.2)
