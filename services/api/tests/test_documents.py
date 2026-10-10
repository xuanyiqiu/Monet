import io
import time

import docx
import pymupdf
import pytest
from fastapi.testclient import TestClient

from monet_api.documents.extract import ExtractError, extract, sentences, sniff_kind
from monet_api.main import create_app
from monet_api.schemas import DocumentKind
from tests.conftest import AUTH


def make_pdf(pages: list[str]) -> bytes:
    doc = pymupdf.open()
    for text in pages:
        page = doc.new_page(width=400, height=600)
        page.insert_text((40, 80), text, fontname="china-s", fontsize=14)
    return doc.tobytes()


def make_docx(paragraphs: list[str]) -> bytes:
    d = docx.Document()
    for p in paragraphs:
        d.add_paragraph(p)
    buf = io.BytesIO()
    d.save(buf)
    return buf.getvalue()


def test_sniff_kind():
    assert sniff_kind(make_pdf(["hi"])) == DocumentKind.pdf
    assert sniff_kind(make_docx(["hola"])) == DocumentKind.docx
    assert sniff_kind("你好，世界".encode()) == DocumentKind.txt
    assert sniff_kind("你好，世界".encode("gb18030")) == DocumentKind.txt
    assert sniff_kind(b"\x89PNG\r\n\x1a\n\x00\x00\x00") is None
    assert sniff_kind(b"PK\x03\x04garbage") is None


def test_extract_pdf_chinese_with_normalized_bboxes():
    pages = extract(DocumentKind.pdf, make_pdf(["老板又在画大饼", "第二页"]), max_pages=10)
    assert len(pages) == 2
    line = pages[0].blocks[0].lines[0]
    assert line.text == "老板又在画大饼"
    x0, y0, x1, y1 = line.bbox
    assert 0 <= x0 < x1 <= 1 and 0 <= y0 < y1 <= 1
    assert pages[0].width == 400


def test_extract_pdf_page_limit():
    with pytest.raises(ExtractError, match="limit"):
        extract(DocumentKind.pdf, make_pdf(["a", "b", "c"]), max_pages=2)


def test_extract_empty_pdf_fails():
    with pytest.raises(ExtractError, match="no text"):
        extract(DocumentKind.pdf, make_pdf([""]), max_pages=5)


def test_extract_docx_paginates_and_splits_sentences():
    paras = ["今天又内卷了。我选择躺平！真的吗？"] + ["Hola. ¿Qué tal? Bien."] * 200
    pages = extract(DocumentKind.docx, make_docx(paras), max_pages=50)
    assert len(pages) > 1
    assert [ln.text for ln in pages[0].blocks[0].lines] == ["今天又内卷了。", "我选择躺平！", "真的吗？"]
    assert pages[0].blocks[0].lines[0].bbox is None


def test_sentences_english_keeps_abbreviation_like_numbers():
    assert sentences("It costs 3.5 dollars. Cheap!") == ["It costs 3.5 dollars.", "Cheap!"]


@pytest.fixture
def doc_client(settings, fake_llm, tmp_path):
    app = create_app(settings.model_copy(update={"blob_dir": tmp_path}), llm=fake_llm)
    with TestClient(app) as c:
        yield c


def wait_ready(c: TestClient, doc_id: str, full: bool = False) -> dict:
    for _ in range(200):
        d = c.get(f"/v1/documents/{doc_id}", headers=AUTH).json()
        if d["status"] == "failed" or (d["status"] == "ready" and (not full or d["progress"] == 1)):
            return d
        time.sleep(0.02)
    raise AssertionError(f"document never became ready: {d}")


def upload(c, data: bytes, name: str, **form):
    return c.post(
        "/v1/documents",
        headers=AUTH,
        files={"file": (name, data, "application/octet-stream")},
        data={"target_lang": "en", **form},
    )


def test_document_flow_txt(doc_client):
    r = upload(doc_client, "老板又在画大饼。\n打工人只能躺平。".encode(), "notes.txt")
    assert r.status_code == 202, r.text
    doc = r.json()
    assert doc["kind"] == "txt" and doc["title"] == "notes" and doc["status"] in ("queued", "processing")

    d = wait_ready(doc_client, doc["id"], full=True)
    assert d["status"] == "ready" and d["page_count"] == 1 and d["progress"] == 1

    page = doc_client.get(f"/v1/documents/{doc['id']}/pages/0", headers=AUTH).json()
    lines = [ln for b in page["blocks"] for ln in b["lines"]]
    assert [ln["text"] for ln in lines] == ["老板又在画大饼。", "打工人只能躺平。"]
    assert lines[0]["annotation"]["translation"] == "[en] 老板又在画大饼。"
    assert lines[0]["annotation"]["expressions"][0]["text"] == "画大饼"
    assert lines[0]["id"] == lines[0]["annotation"]["id"] == "p0.b0.l0"

    assert [x["id"] for x in doc_client.get("/v1/documents", headers=AUTH).json()["items"]] == [doc["id"]]
    f = doc_client.get(f"/v1/documents/{doc['id']}/file", headers=AUTH)
    assert f.status_code == 200 and f.content.decode() == "老板又在画大饼。\n打工人只能躺平。"
    assert doc_client.get(f"/v1/documents/{doc['id']}/pages/5", headers=AUTH).status_code == 404

    assert doc_client.delete(f"/v1/documents/{doc['id']}", headers=AUTH).status_code == 204
    assert doc_client.get(f"/v1/documents/{doc['id']}", headers=AUTH).status_code == 404


def test_document_flow_pdf_keeps_geometry(doc_client):
    doc = upload(doc_client, make_pdf(["绝绝子", "yyds", "躺平", "内卷"]), "slides.pdf").json()
    d = wait_ready(doc_client, doc["id"], full=True)
    assert d["page_count"] == 4
    page = doc_client.get(f"/v1/documents/{doc['id']}/pages/3", headers=AUTH).json()
    assert page["width"] == 400 and page["blocks"][0]["lines"][0]["bbox"] is not None


def test_other_users_cannot_see_documents(doc_client):
    doc = upload(doc_client, b"hello there", "a.txt").json()
    other = {"Authorization": "Bearer dev:someone-else"}
    assert doc_client.get(f"/v1/documents/{doc['id']}", headers=other).status_code == 404
    assert doc_client.get("/v1/documents", headers=other).json()["items"] == []


def test_upload_rejects_binary_and_empty(doc_client):
    assert upload(doc_client, b"\x89PNG\r\n\x1a\n\x00\x00", "x.pdf").status_code == 415
    assert upload(doc_client, b"", "x.txt").status_code == 400


def test_failed_extraction_is_reported(doc_client):
    doc = upload(doc_client, make_pdf([""]), "blank.pdf").json()
    d = wait_ready(doc_client, doc["id"])
    assert d["status"] == "failed" and "no text" in d["error"]
    assert doc_client.get(f"/v1/documents/{doc['id']}/pages/0", headers=AUTH).status_code == 409


def test_upload_too_large(settings, fake_llm, tmp_path):
    app = create_app(settings.model_copy(update={"blob_dir": tmp_path, "max_upload_mb": 0}), llm=fake_llm)
    with TestClient(app) as c:
        assert upload(c, b"hello", "a.txt").status_code == 413
