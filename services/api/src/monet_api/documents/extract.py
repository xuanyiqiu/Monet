"""Turn uploaded bytes into pages → blocks → lines (with normalized geometry for PDFs).

PDF lines keep their bounding boxes so the app can draw translations over the rendered page.
DOCX/TXT are reflowable: a block is a paragraph, a line is a sentence, and pages are ~1800 chars.
"""

import io
import re
import zipfile
from dataclasses import dataclass, field

from monet_api.schemas import DocumentKind

REFLOW_PAGE_CHARS = 1800
_ENDERS = set("。！？!?…")
_CLOSERS = set("”’\"')）」』")


class ExtractError(ValueError):
    pass


@dataclass(slots=True)
class LineData:
    text: str
    bbox: list[float] | None = None


@dataclass(slots=True)
class BlockData:
    lines: list[LineData]
    bbox: list[float] | None = None


@dataclass(slots=True)
class PageData:
    blocks: list[BlockData] = field(default_factory=list)
    width: float | None = None
    height: float | None = None

    def line_count(self) -> int:
        return sum(len(b.lines) for b in self.blocks)

    def to_dict(self) -> dict:
        return {
            "width": self.width,
            "height": self.height,
            "blocks": [
                {"bbox": b.bbox, "lines": [{"text": ln.text, "bbox": ln.bbox} for ln in b.lines]}
                for b in self.blocks
            ],
        }

    @classmethod
    def from_dict(cls, d: dict) -> "PageData":
        return cls(
            width=d.get("width"),
            height=d.get("height"),
            blocks=[
                BlockData([LineData(ln["text"], ln.get("bbox")) for ln in b["lines"]], b.get("bbox"))
                for b in d["blocks"]
            ],
        )


def sniff_kind(data: bytes) -> DocumentKind | None:
    """Decide the type from magic bytes, never from the filename."""
    if data.startswith(b"%PDF-"):
        return DocumentKind.pdf
    if data.startswith(b"PK\x03\x04"):
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                if "word/document.xml" in z.namelist():
                    return DocumentKind.docx
        except zipfile.BadZipFile:
            return None
        return None
    if decode_text(data) is not None:
        return DocumentKind.txt
    return None


def decode_text(data: bytes) -> str | None:
    if b"\x00" in data[:4096] and not data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return None
    for enc in ("utf-8-sig", "utf-16", "gb18030"):
        try:
            text = data.decode(enc)
        except UnicodeDecodeError:
            continue
        printable = sum(1 for c in text[:5000] if c.isprintable() or c in "\n\r\t")
        if text and printable / min(len(text), 5000) > 0.95:
            return text
    return None


def _norm(rect, w: float, h: float) -> list[float]:
    x0, y0, x1, y1 = rect
    clamp = lambda v: round(min(1.0, max(0.0, v)), 5)  # noqa: E731
    return [clamp(x0 / w), clamp(y0 / h), clamp(x1 / w), clamp(y1 / h)]


def extract_pdf(data: bytes, max_pages: int) -> list[PageData]:
    import pymupdf

    try:
        doc = pymupdf.open(stream=data, filetype="pdf")
    except Exception as e:
        raise ExtractError(f"cannot open PDF: {e}") from e
    if doc.needs_pass:
        raise ExtractError("PDF is password-protected")
    if doc.page_count > max_pages:
        raise ExtractError(f"PDF has {doc.page_count} pages; the limit is {max_pages}")
    pages = []
    for page in doc:
        w, h = page.rect.width, page.rect.height
        pd = PageData(width=round(w, 2), height=round(h, 2))
        for block in page.get_text("dict", sort=True)["blocks"]:
            if block.get("type") != 0:
                continue
            lines = []
            for line in block["lines"]:
                text = "".join(span["text"] for span in line["spans"]).strip()
                if text:
                    lines.append(LineData(text, _norm(line["bbox"], w, h)))
            if lines:
                pd.blocks.append(BlockData(lines, _norm(block["bbox"], w, h)))
        pages.append(pd)
    return pages


def sentences(paragraph: str) -> list[str]:
    """Split after 。！？!?… (plus closing quotes) and after "." followed by a space; keep 3.5 intact."""
    out, cur, i, n = [], [], 0, len(paragraph)
    while i < n:
        ch = paragraph[i]
        cur.append(ch)
        end = ch in _ENDERS or (ch == "." and (i + 1 == n or paragraph[i + 1].isspace()))
        if end:
            while i + 1 < n and (paragraph[i + 1] in _ENDERS or paragraph[i + 1] in _CLOSERS):
                i += 1
                cur.append(paragraph[i])
            out.append("".join(cur).strip())
            cur = []
        i += 1
    if "".join(cur).strip():
        out.append("".join(cur).strip())
    return [p for p in out if p] or [paragraph.strip()]


def _paginate(paragraphs: list[str]) -> list[PageData]:
    pages: list[PageData] = []
    current, size = PageData(), 0
    for para in paragraphs:
        para = para.strip()
        if not para:
            continue
        if size and size + len(para) > REFLOW_PAGE_CHARS:
            pages.append(current)
            current, size = PageData(), 0
        current.blocks.append(BlockData([LineData(s) for s in sentences(para)]))
        size += len(para)
    if current.blocks:
        pages.append(current)
    return pages


def extract_docx(data: bytes) -> list[PageData]:
    import docx

    try:
        d = docx.Document(io.BytesIO(data))
    except Exception as e:
        raise ExtractError(f"cannot open DOCX: {e}") from e
    paragraphs = [p.text for p in d.paragraphs]
    for table in d.tables:
        for row in table.rows:
            paragraphs.append(" · ".join(c.text.strip() for c in row.cells if c.text.strip()))
    return _paginate(paragraphs)


def extract_txt(data: bytes) -> list[PageData]:
    text = decode_text(data)
    if text is None:
        raise ExtractError("cannot decode text file")
    return _paginate(re.split(r"\r?\n", text))


def extract(kind: DocumentKind, data: bytes, max_pages: int) -> list[PageData]:
    if kind == DocumentKind.pdf:
        pages = extract_pdf(data, max_pages)
    elif kind == DocumentKind.docx:
        pages = extract_docx(data)
    else:
        pages = extract_txt(data)
    if len(pages) > max_pages:
        raise ExtractError(f"document has {len(pages)} pages; the limit is {max_pages}")
    if not any(p.blocks for p in pages):
        raise ExtractError("no text found (scanned PDFs are not supported yet)")
    return pages
