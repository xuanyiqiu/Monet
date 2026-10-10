import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path
from uuid import UUID

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from monet_api import __version__, db, errors
from monet_api.annotator import Annotator
from monet_api.auth import RateLimiter
from monet_api.cache import MemoryCache, TieredCache
from monet_api.config import Settings, get_settings
from monet_api.documents.service import PROCESS_JOB, DocumentService
from monet_api.documents.store import DocumentRepo, LocalBlobStore, MemoryDocumentRepo
from monet_api.feedback import MemoryFeedbackRepo
from monet_api.jobs import Job, PgJobQueue, Worker
from monet_api.llm import LLMProvider, build_provider
from monet_api.nlp.lexicon import Lexicon, load_lexicon
from monet_api.nlp.tokenize import Tokenizer
from monet_api.routers import core, documents

log = logging.getLogger("monet")
_STATIC = Path(__file__).parent / "static"

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
    engine = db.create_engine(settings.database_url) if settings.database_url else None

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        worker = None
        if engine is not None:
            await db.upgrade(settings.database_url)  # idempotent; same path in dev and prod
            docs = app.state.documents

            async def process_document(payload: dict, job: Job) -> None:
                await docs.process(UUID(payload["doc_id"]), last_attempt=job.attempts >= job.max_attempts)

            worker = Worker(app.state.queue, {PROCESS_JOB: process_document})
            worker.start()
        yield
        if worker is not None:
            await worker.stop()
        await app.state.documents.join()
        if engine is not None:
            await engine.dispose()

    app = FastAPI(
        lifespan=lifespan,
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
    repo: DocumentRepo = db.PgDocumentRepo(engine) if engine is not None else MemoryDocumentRepo()
    app.state.queue = PgJobQueue(engine) if engine is not None else None
    app.state.feedback = db.PgFeedbackRepo(engine) if engine is not None else MemoryFeedbackRepo()
    app.state.documents = DocumentService(
        repo, LocalBlobStore(settings.blob_dir), app.state.annotator, settings.max_pages, app.state.queue
    )

    errors.install(app, expose_errors=settings.is_dev)
    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware, allow_origins=settings.cors_origins, allow_methods=["*"], allow_headers=["*"]
        )
    app.include_router(core.router)
    app.include_router(documents.router)

    if settings.auth_mode == "dev":

        @app.get("/playground", include_in_schema=False)
        async def playground() -> FileResponse:
            return FileResponse(_STATIC / "playground.html")

    return app


def run() -> None:
    import uvicorn

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    uvicorn.run(create_app(), host="0.0.0.0", port=8000)  # noqa: S104 (emulator reaches host via 10.0.2.2)
