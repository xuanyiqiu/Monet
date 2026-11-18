"""Storage for documents: blobs (original files) and records (metadata + extracted/annotated pages).

In-memory/local-disk implementations for dev and tests; Postgres + Supabase Storage come in A-05.
"""

import asyncio
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Protocol
from uuid import UUID

import httpx

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


class SupabaseBlobStore:
    """BlobStore backed by the Supabase Storage REST API."""

    def __init__(
        self,
        base_url: str,
        service_key: str,
        bucket: str = "documents",
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.service_key = service_key
        self.bucket = bucket
        self._http = client

    def _client(self) -> httpx.AsyncClient:
        if self._http is None:
            self._http = httpx.AsyncClient(timeout=httpx.Timeout(20.0))
        return self._http

    def _url(self, key: str) -> str:
        if ".." in key or key.startswith("/"):
            raise ValueError("invalid blob key")
        return f"{self.base_url}/storage/v1/object/{self.bucket}/{key}"

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.service_key}", "apikey": self.service_key}

    async def put(self, key: str, data: bytes) -> None:
        response = await self._client().post(
            self._url(key),
            content=data,
            headers={
                **self._headers(),
                "x-upsert": "true",
                "Content-Type": "application/octet-stream",
            },
        )
        if not response.is_success:
            raise RuntimeError(f"storage upload failed with status {response.status_code}")

    async def get(self, key: str) -> bytes:
        response = await self._client().get(self._url(key), headers=self._headers())
        if response.status_code == 404:
            raise FileNotFoundError(key)
        if not response.is_success:
            raise RuntimeError(f"storage download failed with status {response.status_code}")
        return response.content

    async def delete(self, key: str) -> None:
        response = await self._client().delete(self._url(key), headers=self._headers())
        if response.status_code == 404:
            return
        if not response.is_success:
            raise RuntimeError(f"storage delete failed with status {response.status_code}")

    async def aclose(self) -> None:
        if self._http is not None:
            await self._http.aclose()


@dataclass
class DocRecord:
    doc: Document
    owner: str
    blob_key: str
    mime: str


class DocumentRepo(Protocol):
    async def add(self, rec: DocRecord) -> None: ...
    async def get(self, doc_id: UUID) -> DocRecord | None: ...
    async def list_owned(self, owner: str) -> list[DocRecord]: ...
    async def update(self, doc_id: UUID, **fields) -> Document | None: ...
    async def save_extracted(self, doc_id: UUID, pages: list[PageData]) -> None: ...
    async def get_extracted(self, doc_id: UUID, index: int) -> PageData | None: ...
    async def save_page(self, doc_id: UUID, page: DocumentPage) -> None: ...
    async def get_page(self, doc_id: UUID, index: int) -> DocumentPage | None: ...
    async def delete(self, doc_id: UUID) -> None: ...


class MemoryDocumentRepo:
    def __init__(self) -> None:
        self._recs: dict[UUID, DocRecord] = {}
        self._extracted: dict[UUID, list[PageData]] = {}
        self._pages: dict[tuple[UUID, int], DocumentPage] = {}

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

    async def save_extracted(self, doc_id: UUID, pages: list[PageData]) -> None:
        self._extracted[doc_id] = pages

    async def get_extracted(self, doc_id: UUID, index: int) -> PageData | None:
        pages = self._extracted.get(doc_id, [])
        return pages[index] if 0 <= index < len(pages) else None

    async def save_page(self, doc_id: UUID, page: DocumentPage) -> None:
        if doc_id in self._recs:
            self._pages[(doc_id, page.index)] = page

    async def get_page(self, doc_id: UUID, index: int) -> DocumentPage | None:
        return self._pages.get((doc_id, index))

    async def delete(self, doc_id: UUID) -> None:
        self._recs.pop(doc_id, None)
        self._extracted.pop(doc_id, None)
        for k in [k for k in self._pages if k[0] == doc_id]:
            del self._pages[k]
