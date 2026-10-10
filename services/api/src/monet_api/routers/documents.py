from pathlib import Path
from uuid import UUID

from fastapi import APIRouter, Depends, File, Form, Request, Response, UploadFile

from monet_api.auth import current_user, rate_limited_user
from monet_api.documents.extract import sniff_kind
from monet_api.documents.service import DocumentService
from monet_api.documents.store import DocRecord
from monet_api.errors import Problem
from monet_api.schemas import Document, DocumentList, DocumentPage, DocumentStatus, Lang, SourceLang

router = APIRouter(prefix="/v1/documents")


def service(request: Request) -> DocumentService:
    return request.app.state.documents


async def owned(
    document_id: UUID, user: str = Depends(current_user), svc: DocumentService = Depends(service)
) -> DocRecord:
    rec = await svc.owned(user, document_id)
    if rec is None:
        raise Problem(404, "Document not found")
    return rec


@router.get("", response_model=DocumentList, operation_id="listDocuments")
async def list_documents(
    user: str = Depends(current_user), svc: DocumentService = Depends(service)
) -> DocumentList:
    return DocumentList(items=[r.doc for r in await svc.repo.list_owned(user)])


@router.post("", response_model=Document, status_code=202, operation_id="uploadDocument")
async def upload_document(
    request: Request,
    file: UploadFile = File(...),
    target_lang: Lang = Form(...),
    source_lang: SourceLang = Form(SourceLang.auto),
    title: str | None = Form(None, max_length=200),
    user: str = Depends(rate_limited_user),
    svc: DocumentService = Depends(service),
) -> Document:
    limit = request.app.state.settings.max_upload_mb * 1024 * 1024
    data = await file.read(limit + 1)
    if len(data) > limit:
        raise Problem(413, "File too large", f"Max {request.app.state.settings.max_upload_mb} MB")
    if not data:
        raise Problem(400, "Empty file")
    kind = sniff_kind(data)
    if kind is None:
        raise Problem(415, "Unsupported file type", "Upload a PDF, DOCX or plain-text file.")
    name = title or Path(file.filename or "Untitled").stem or "Untitled"
    return await svc.create(user, name[:200], kind, data, source_lang, target_lang)


@router.get("/{document_id}", response_model=Document, operation_id="getDocument")
async def get_document(rec: DocRecord = Depends(owned)) -> Document:
    return rec.doc


@router.delete("/{document_id}", status_code=204, operation_id="deleteDocument")
async def delete_document(
    rec: DocRecord = Depends(owned), svc: DocumentService = Depends(service)
) -> Response:
    await svc.delete(rec)
    return Response(status_code=204)


@router.get(
    "/{document_id}/file",
    operation_id="getDocumentFile",
    responses={200: {"content": {"application/octet-stream": {}}}},
)
async def get_document_file(
    rec: DocRecord = Depends(owned), svc: DocumentService = Depends(service)
) -> Response:
    data = await svc.blobs.get(rec.blob_key)
    return Response(
        data,
        media_type=rec.mime,
        headers={"Content-Disposition": f'inline; filename="{rec.doc.id}.{rec.doc.kind.value}"'},
    )


@router.get("/{document_id}/pages/{page_index}", response_model=DocumentPage, operation_id="getDocumentPage")
async def get_document_page(
    page_index: int, rec: DocRecord = Depends(owned), svc: DocumentService = Depends(service)
) -> DocumentPage:
    if rec.doc.status != DocumentStatus.ready:
        raise Problem(409, "Document not ready", f"Status: {rec.doc.status.value}")
    if not 0 <= page_index < (rec.doc.page_count or 0):
        raise Problem(404, "Page not found")
    return await svc.page(rec, page_index)
