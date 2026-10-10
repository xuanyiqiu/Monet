"""Document lifecycle: upload → extract → annotate pages → ready.

A document becomes `ready` as soon as it is extracted and its first READY_AFTER_PAGES pages are
annotated, so it opens quickly even on a rate-limited free LLM tier. Remaining pages are annotated in
the background (`progress` keeps rising to 1.0); a page requested before that is annotated on demand.
"""

import asyncio
import logging
from uuid import UUID, uuid4

from monet_api.annotator import Annotator
from monet_api.documents.extract import ExtractError, PageData, extract
from monet_api.documents.store import BlobStore, DocRecord, DocumentRepo, now
from monet_api.schemas import (
    AnnotateLinesRequest,
    AnnotationContext,
    Document,
    DocumentBlock,
    DocumentKind,
    DocumentLine,
    DocumentPage,
    DocumentStatus,
    Lang,
    LineInput,
    SourceLang,
)

log = logging.getLogger(__name__)
READY_AFTER_PAGES = 3
MIME = {
    DocumentKind.pdf: "application/pdf",
    DocumentKind.docx: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    DocumentKind.txt: "text/plain; charset=utf-8",
}


class DocumentService:
    def __init__(self, repo: DocumentRepo, blobs: BlobStore, annotator: Annotator, max_pages: int) -> None:
        self.repo = repo
        self.blobs = blobs
        self.annotator = annotator
        self.max_pages = max_pages
        self._tasks: set[asyncio.Task] = set()
        self._page_locks: dict[tuple[UUID, int], asyncio.Lock] = {}

    # ------------------------------------------------------------------ commands

    async def create(
        self,
        owner: str,
        title: str,
        kind: DocumentKind,
        data: bytes,
        source_lang: SourceLang,
        target_lang: Lang,
    ) -> Document:
        doc_id = uuid4()
        ts = now()
        doc = Document(
            id=doc_id,
            title=title,
            kind=kind,
            status=DocumentStatus.queued,
            progress=0.0,
            size_bytes=len(data),
            source_lang=source_lang,
            target_lang=target_lang,
            created_at=ts,
            updated_at=ts,
        )
        key = f"{owner}/{doc_id}.{kind.value}"
        await self.blobs.put(key, data)
        await self.repo.add(DocRecord(doc=doc, owner=owner, blob_key=key, mime=MIME[kind]))
        task = asyncio.create_task(self._process(doc_id))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return doc

    async def delete(self, rec: DocRecord) -> None:
        await self.blobs.delete(rec.blob_key)
        await self.repo.delete(rec.doc.id)

    async def join(self) -> None:
        """Wait for background work (tests, graceful shutdown)."""
        while self._tasks:
            await asyncio.gather(*list(self._tasks), return_exceptions=True)

    # ------------------------------------------------------------------ queries

    async def owned(self, owner: str, doc_id: UUID) -> DocRecord | None:
        rec = await self.repo.get(doc_id)
        return rec if rec and rec.owner == owner else None

    async def page(self, rec: DocRecord, index: int) -> DocumentPage:
        if (page := await self.repo.get_page(rec.doc.id, index)) is not None:
            return page
        lock = self._page_locks.setdefault((rec.doc.id, index), asyncio.Lock())
        async with lock:
            page = await self.repo.get_page(rec.doc.id, index)
            if page is None:
                page = await self._annotate_page(rec, index)
                await self.repo.save_page(rec.doc.id, page)
        return page

    # ------------------------------------------------------------------ pipeline

    async def _process(self, doc_id: UUID) -> None:
        rec = await self.repo.get(doc_id)
        if rec is None:
            return
        try:
            await self.repo.update(doc_id, status=DocumentStatus.processing, progress=0.02)
            data = await self.blobs.get(rec.blob_key)
            pages = await asyncio.to_thread(extract, rec.doc.kind, data, self.max_pages)
            await self.repo.save_extracted(doc_id, pages)
            await self.repo.update(doc_id, page_count=len(pages), progress=0.05)
            total = len(pages)
            for i in range(total):
                if (rec := await self.repo.get(doc_id)) is None:
                    return  # deleted while processing
                await self.page(rec, i)
                fields = {"progress": round((i + 1) / total, 3)}
                if i + 1 == min(READY_AFTER_PAGES, total):
                    fields["status"] = DocumentStatus.ready
                await self.repo.update(doc_id, **fields)
        except ExtractError as e:
            await self.repo.update(doc_id, status=DocumentStatus.failed, error=str(e))
        except Exception:
            log.exception("document %s failed", doc_id)
            await self.repo.update(
                doc_id, status=DocumentStatus.failed, error="Processing failed. Try again."
            )

    async def _annotate_page(self, rec: DocRecord, index: int) -> DocumentPage:
        page: PageData | None = await self.repo.get_extracted(rec.doc.id, index)
        if page is None:
            raise ExtractError(f"page {index} was not extracted")
        flat = [(b, li, line) for b, block in enumerate(page.blocks) for li, line in enumerate(block.lines)]
        inputs = [LineInput(id=f"{b}.{li}", text=line.text[:500]) for b, li, line in flat]
        annotated = {}
        for c in range(0, len(inputs), 50):
            res = await self.annotator.annotate_lines(
                AnnotateLinesRequest(
                    lines=inputs[c : c + 50],
                    source_lang=rec.doc.source_lang,
                    target_lang=rec.doc.target_lang,
                    context=AnnotationContext(app=rec.doc.kind.value),
                )
            )
            annotated.update({ln.id: ln for ln in res.lines})
        blocks = []
        for b, block in enumerate(page.blocks):
            lines = []
            for li, line in enumerate(block.lines):
                ann = annotated[f"{b}.{li}"].model_copy(update={"id": f"p{index}.b{b}.l{li}"})
                lines.append(DocumentLine(id=ann.id, text=line.text, bbox=line.bbox, annotation=ann))
            blocks.append(DocumentBlock(id=f"p{index}.b{b}", bbox=block.bbox, lines=lines))
        return DocumentPage(
            document_id=rec.doc.id, index=index, width=page.width, height=page.height, blocks=blocks
        )
