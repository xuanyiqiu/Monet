"""Postgres-backed repo tests. Need Monet's own DB: `docker compose up -d db` (port 54329).
They use a separate database `monet_test` and are skipped when the server is unreachable."""

import asyncio
import io
import time
from uuid import uuid4

import pytest
import sqlalchemy as sa
import sqlalchemy.ext.asyncio
from fastapi.testclient import TestClient

from monet_api import db
from monet_api.documents.extract import BlockData, LineData, PageData
from monet_api.documents.store import DocRecord, now
from monet_api.main import create_app
from monet_api.schemas import Document, DocumentKind, DocumentStatus, Lang, SourceLang
from tests.conftest import AUTH

ADMIN_URL = "postgresql+asyncpg://monet:monet@127.0.0.1:54329/monet"
TEST_URL = "postgresql+asyncpg://monet:monet@127.0.0.1:54329/monet_test"

pytestmark = pytest.mark.db


async def _ensure_test_db() -> bool:
    engine = sa.ext.asyncio.create_async_engine(ADMIN_URL, isolation_level="AUTOCOMMIT")
    try:
        async with engine.connect() as c:
            exists = (
                await c.execute(sa.text("select 1 from pg_database where datname='monet_test'"))
            ).first()
            if not exists:
                await c.execute(sa.text("create database monet_test"))
        return True
    except Exception:
        return False
    finally:
        await engine.dispose()


@pytest.fixture(scope="module")
def pg_url():
    if not asyncio.run(_ensure_test_db()):
        pytest.skip("Monet's Postgres is not running (docker compose up -d db)")
    return TEST_URL


@pytest.fixture
async def repo(pg_url):
    await db.upgrade(pg_url)
    engine = db.create_engine(pg_url)
    async with engine.begin() as c:
        await c.execute(sa.text("truncate documents cascade"))
    yield db.PgDocumentRepo(engine)
    await engine.dispose()


def make_record(owner="alice") -> DocRecord:
    ts = now()
    doc = Document(
        id=uuid4(), title="t", kind=DocumentKind.txt, status=DocumentStatus.queued, progress=0.0,
        size_bytes=5, source_lang=SourceLang.auto, target_lang=Lang.en, created_at=ts, updated_at=ts,
    )  # fmt: skip
    return DocRecord(doc=doc, owner=owner, blob_key=f"{owner}/x.txt", mime="text/plain")


async def test_repo_crud_and_ownership_listing(repo):
    a, b = make_record("alice"), make_record("bob")
    await repo.add(a)
    await repo.add(b)
    got = await repo.get(a.doc.id)
    assert got and got.owner == "alice" and got.doc.title == "t" and got.doc.status == DocumentStatus.queued
    assert [r.doc.id for r in await repo.list_owned("alice")] == [a.doc.id]

    before = got.doc.updated_at
    upd = await repo.update(a.doc.id, status=DocumentStatus.processing, progress=0.5, page_count=2)
    assert upd.status == DocumentStatus.processing and upd.page_count == 2 and upd.updated_at >= before
    assert await repo.update(uuid4(), progress=1) is None

    await repo.delete(a.doc.id)
    assert await repo.get(a.doc.id) is None


async def test_repo_pages_roundtrip_and_cascade(repo):
    from monet_api.schemas import DocumentPage

    rec = make_record()
    await repo.add(rec)
    pages = [
        PageData(
            blocks=[BlockData([LineData("你好", [0.1, 0.1, 0.5, 0.2])], [0, 0, 1, 1])], width=400, height=600
        ),
        PageData(blocks=[BlockData([LineData("hola")])]),
    ]
    await repo.save_extracted(rec.doc.id, pages)
    p0 = await repo.get_extracted(rec.doc.id, 0)
    assert p0.blocks[0].lines[0].text == "你好" and p0.blocks[0].lines[0].bbox == [0.1, 0.1, 0.5, 0.2]
    assert (await repo.get_extracted(rec.doc.id, 1)).blocks[0].lines[0].bbox is None
    assert await repo.get_extracted(rec.doc.id, 2) is None

    assert await repo.get_page(rec.doc.id, 0) is None
    await repo.save_page(rec.doc.id, DocumentPage(document_id=rec.doc.id, index=0, blocks=[]))
    assert (await repo.get_page(rec.doc.id, 0)).index == 0

    await repo.delete(rec.doc.id)
    assert await repo.get_extracted(rec.doc.id, 0) is None


def test_full_flow_through_api_on_postgres(settings, fake_llm, pg_url, tmp_path):
    app = create_app(settings.model_copy(update={"database_url": pg_url, "blob_dir": tmp_path}), llm=fake_llm)
    with TestClient(app) as c:
        r = c.post(
            "/v1/documents",
            headers=AUTH,
            files={"file": ("n.txt", io.BytesIO("老板又在画大饼。".encode()), "text/plain")},
            data={"target_lang": "en"},
        )
        doc = r.json()
        for _ in range(200):
            d = c.get(f"/v1/documents/{doc['id']}", headers=AUTH).json()
            if d["status"] == "ready" and d["progress"] == 1:
                break
            time.sleep(0.02)
        assert d["status"] == "ready" and d["page_count"] == 1
        page = c.get(f"/v1/documents/{doc['id']}/pages/0", headers=AUTH).json()
        assert page["blocks"][0]["lines"][0]["annotation"]["expressions"][0]["text"] == "画大饼"
        assert c.delete(f"/v1/documents/{doc['id']}", headers=AUTH).status_code == 204


async def test_feedback_repo_counts_reports(pg_url):
    from monet_api.feedback import FeedbackItem

    await db.upgrade(pg_url)
    engine = db.create_engine(pg_url)
    try:
        async with engine.begin() as c:
            await c.execute(sa.text("truncate feedback"))
        repo = db.PgFeedbackRepo(engine)
        for text in ("画大饼", "画大饼", "yyds"):
            await repo.add(FeedbackItem(user_id="u", text=text, target_lang="en", comment="wrong"))
        assert await repo.top_reported(5) == [("画大饼", 2), ("yyds", 1)]
    finally:
        await engine.dispose()
