"""Storage for documents: blobs (original files) and records (metadata + extracted/annotated pages).

In-memory/local-disk implementations for dev and tests; Postgres + Supabase Storage come in A-05.
"""

import asyncio
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Protocol
from uuid import UUID

from monet_api.documents.extract import PageData
from monet_api.schemas import Document, DocumentPage


def now() -> datetime:
    return datetime.now(UTC)


class BlobStore(Protocol):
    async def put(self, key: str, data: bytes) -> None: ...
    async def get(self, key: str) -> bytes: ...
    async def delete(self, key: str) -> None: ...


class LocalBlobStore:
    def __init__(self, root: Path) -> None:
        self.root = root

    def _path(self, key: str) -> Path:
        p = (self.root / key).resolve()
        if not p.is_relative_to(self.root.resolve()):
            raise ValueError("invalid blob key")
        return p

    async def put(self, key: str, data: bytes) -> None:
        p = self._path(key)
        p.parent.mkdir(parents=True, exist_ok=True)
        await asyncio.to_thread(p.write_bytes, data)

    async def get(self, key: str) -> bytes:
        return await asyncio.to_thread(self._path(key).read_bytes)

    async def delete(self, key: str) -> None:
        self._path(key).unlink(missing_ok=True)


@dataclass
class DocRecord:
    doc: Document
    owner: str
    blob_key: str
    mime: str
    extracted: list[PageData] = field(default_factory=list)
    pages: dict[int, DocumentPage] = field(default_factory=dict)


class DocumentRepo(Protocol):
    async def add(self, rec: DocRecord) -> None: ...
    async def get(self, doc_id: UUID) -> DocRecord | None: ...
    async def list_owned(self, owner: str) -> list[DocRecord]: ...
    async def update(self, doc_id: UUID, **fields) -> Document | None: ...
    async def set_extracted(self, doc_id: UUID, pages: list[PageData]) -> None: ...
    async def save_page(self, doc_id: UUID, page: DocumentPage) -> None: ...
    async def delete(self, doc_id: UUID) -> None: ...


class MemoryDocumentRepo:
    def __init__(self) -> None:
        self._recs: dict[UUID, DocRecord] = {}

    async def add(self, rec: DocRecord) -> None:
        self._recs[rec.doc.id] = rec

    async def get(self, doc_id: UUID) -> DocRecord | None:
        return self._recs.get(doc_id)

    async def list_owned(self, owner: str) -> list[DocRecord]:
        recs = [r for r in self._recs.values() if r.owner == owner]
        return sorted(recs, key=lambda r: r.doc.created_at, reverse=True)

    async def update(self, doc_id: UUID, **fields) -> Document | None:
        rec = self._recs.get(doc_id)
        if rec is None:
            return None
        rec.doc = rec.doc.model_copy(update={**fields, "updated_at": now()})
        return rec.doc

    async def set_extracted(self, doc_id: UUID, pages: list[PageData]) -> None:
        if rec := self._recs.get(doc_id):
            rec.extracted = pages

    async def save_page(self, doc_id: UUID, page: DocumentPage) -> None:
        if rec := self._recs.get(doc_id):
            rec.pages[page.index] = page

    async def delete(self, doc_id: UUID) -> None:
        self._recs.pop(doc_id, None)
