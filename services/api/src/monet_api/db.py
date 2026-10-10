"""SQLAlchemy Core schema + Postgres-backed DocumentRepo + migration runner.

Schema changes go through Alembic (migrations/versions); `upgrade()` is the only way tables are created.
"""

import asyncio
import json
from pathlib import Path
from uuid import UUID, uuid4

import sqlalchemy as sa
from alembic import command
from alembic.config import Config
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine

from monet_api.config import API_ROOT
from monet_api.documents.extract import PageData
from monet_api.documents.store import DocRecord, now
from monet_api.schemas import Document, DocumentPage

metadata = sa.MetaData()

documents = sa.Table(
    "documents",
    metadata,
    sa.Column("id", PG_UUID(as_uuid=True), primary_key=True),
    sa.Column("owner", sa.Text, nullable=False),
    sa.Column("title", sa.Text, nullable=False),
    sa.Column("kind", sa.Text, nullable=False),
    sa.Column("status", sa.Text, nullable=False),
    sa.Column("progress", sa.Float, nullable=False, server_default="0"),
    sa.Column("page_count", sa.Integer),
    sa.Column("size_bytes", sa.BigInteger, nullable=False, server_default="0"),
    sa.Column("source_lang", sa.Text, nullable=False),
    sa.Column("target_lang", sa.Text, nullable=False),
    sa.Column("error", sa.Text),
    sa.Column("blob_key", sa.Text, nullable=False),
    sa.Column("mime", sa.Text, nullable=False),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    sa.Index("ix_documents_owner_created", "owner", sa.text("created_at DESC")),
)

document_pages = sa.Table(
    "document_pages",
    metadata,
    sa.Column("document_id", PG_UUID(as_uuid=True), sa.ForeignKey("documents.id", ondelete="CASCADE")),
    sa.Column("index", sa.Integer, nullable=False),
    sa.Column("extracted", JSONB, nullable=False),
    sa.Column("annotated", JSONB),
    sa.PrimaryKeyConstraint("document_id", "index"),
)

jobs = sa.Table(
    "jobs",
    metadata,
    sa.Column("id", PG_UUID(as_uuid=True), primary_key=True),
    sa.Column("kind", sa.Text, nullable=False),
    sa.Column("payload", JSONB, nullable=False),
    sa.Column("status", sa.Text, nullable=False, server_default="queued"),
    sa.Column("attempts", sa.Integer, nullable=False, server_default="0"),
    sa.Column("max_attempts", sa.Integer, nullable=False, server_default="3"),
    sa.Column("run_at", sa.DateTime(timezone=True), nullable=False),
    sa.Column("locked_by", sa.Text),
    sa.Column("locked_until", sa.DateTime(timezone=True)),
    sa.Column("last_error", sa.Text),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    sa.Index("ix_jobs_claim", "status", "run_at"),
)

feedback = sa.Table(
    "feedback",
    metadata,
    sa.Column("id", PG_UUID(as_uuid=True), primary_key=True),
    sa.Column("user_id", sa.Text, nullable=False),
    sa.Column("text", sa.Text, nullable=False),
    sa.Column("translation", sa.Text),
    sa.Column("target_lang", sa.Text, nullable=False),
    sa.Column("comment", sa.Text),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    sa.Index("ix_feedback_created", sa.text("created_at DESC")),
)

_DOC_FIELDS = [
    "id", "title", "kind", "status", "progress", "page_count", "size_bytes",
    "source_lang", "target_lang", "error", "created_at", "updated_at",
]  # fmt: skip


def create_engine(url: str) -> AsyncEngine:
    return create_async_engine(url, pool_size=5, max_overflow=5, pool_pre_ping=True)


def _alembic_config(url: str) -> Config:
    cfg = Config(str(API_ROOT / "alembic.ini"))
    cfg.set_main_option("script_location", str(API_ROOT / "migrations"))
    cfg.set_main_option("sqlalchemy.url", url.replace("%", "%%"))
    return cfg


async def upgrade(url: str, revision: str = "head") -> None:
    await asyncio.to_thread(command.upgrade, _alembic_config(url), revision)


def _to_record(row) -> DocRecord:
    doc = Document.model_validate({k: row[k] for k in _DOC_FIELDS})
    return DocRecord(doc=doc, owner=row["owner"], blob_key=row["blob_key"], mime=row["mime"])


class PgDocumentRepo:
    def __init__(self, engine: AsyncEngine) -> None:
        self.engine = engine

    async def add(self, rec: DocRecord) -> None:
        values = rec.doc.model_dump(mode="python")
        values.update(owner=rec.owner, blob_key=rec.blob_key, mime=rec.mime)
        async with self.engine.begin() as c:
            await c.execute(sa.insert(documents).values(**values))

    async def get(self, doc_id: UUID) -> DocRecord | None:
        async with self.engine.connect() as c:
            row = (await c.execute(sa.select(documents).where(documents.c.id == doc_id))).mappings().first()
        return _to_record(row) if row else None

    async def list_owned(self, owner: str) -> list[DocRecord]:
        q = sa.select(documents).where(documents.c.owner == owner).order_by(documents.c.created_at.desc())
        async with self.engine.connect() as c:
            rows = (await c.execute(q)).mappings().all()
        return [_to_record(r) for r in rows]

    async def update(self, doc_id: UUID, **fields) -> Document | None:
        q = (
            sa.update(documents)
            .where(documents.c.id == doc_id)
            .values(**fields, updated_at=now())
            .returning(*[documents.c[k] for k in _DOC_FIELDS])
        )
        async with self.engine.begin() as c:
            row = (await c.execute(q)).mappings().first()
        return Document.model_validate(dict(row)) if row else None

    async def save_extracted(self, doc_id: UUID, pages: list[PageData]) -> None:
        rows = [{"document_id": doc_id, "index": i, "extracted": p.to_dict()} for i, p in enumerate(pages)]
        async with self.engine.begin() as c:
            await c.execute(sa.delete(document_pages).where(document_pages.c.document_id == doc_id))
            if rows:
                await c.execute(sa.insert(document_pages), rows)

    async def get_extracted(self, doc_id: UUID, index: int) -> PageData | None:
        q = sa.select(document_pages.c.extracted).where(
            document_pages.c.document_id == doc_id, document_pages.c.index == index
        )
        async with self.engine.connect() as c:
            row = (await c.execute(q)).first()
        return PageData.from_dict(row[0]) if row else None

    async def save_page(self, doc_id: UUID, page: DocumentPage) -> None:
        q = (
            sa.update(document_pages)
            .where(document_pages.c.document_id == doc_id, document_pages.c.index == page.index)
            .values(annotated=json.loads(page.model_dump_json()))
        )
        async with self.engine.begin() as c:
            await c.execute(q)

    async def get_page(self, doc_id: UUID, index: int) -> DocumentPage | None:
        q = sa.select(document_pages.c.annotated).where(
            document_pages.c.document_id == doc_id, document_pages.c.index == index
        )
        async with self.engine.connect() as c:
            row = (await c.execute(q)).first()
        return DocumentPage.model_validate(row[0]) if row and row[0] else None

    async def delete(self, doc_id: UUID) -> None:
        async with self.engine.begin() as c:
            await c.execute(sa.delete(documents).where(documents.c.id == doc_id))


class PgFeedbackRepo:
    def __init__(self, engine: AsyncEngine) -> None:
        self.engine = engine

    async def add(self, item) -> UUID:
        fid = uuid4()
        async with self.engine.begin() as c:
            await c.execute(
                sa.insert(feedback).values(
                    id=fid, user_id=item.user_id, text=item.text, translation=item.translation,
                    target_lang=item.target_lang, comment=item.comment, created_at=now(),
                )
            )  # fmt: skip
        return fid

    async def top_reported(self, limit: int = 20) -> list[tuple[str, int]]:
        n = sa.func.count().label("n")
        q = sa.select(feedback.c.text, n).group_by(feedback.c.text).order_by(n.desc()).limit(limit)
        async with self.engine.connect() as c:
            return [(t, int(k)) for t, k in (await c.execute(q)).all()]


def migrations_dir() -> Path:
    return API_ROOT / "migrations"
