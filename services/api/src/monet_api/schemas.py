"""Pydantic models mirroring contracts/openapi.yaml. Keep names and fields in sync with the contract."""

import warnings
from datetime import datetime
from enum import StrEnum
from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class Lang(StrEnum):
    zh = "zh"
    es = "es"
    en = "en"


class SourceLang(StrEnum):
    auto = "auto"
    zh = "zh"
    es = "es"
    en = "en"


class Depth(StrEnum):
    fast = "fast"
    full = "full"


class TokenKind(StrEnum):
    word = "word"
    punct = "punct"
    space = "space"
    number = "number"
    symbol = "symbol"


class ExpressionKind(StrEnum):
    chengyu = "chengyu"
    idiom = "idiom"
    proverb = "proverb"
    xiehouyu = "xiehouyu"
    meme = "meme"
    slang = "slang"
    wordplay = "wordplay"
    classical = "classical"
    other = "other"


class ExpressionSource(StrEnum):
    dictionary = "dictionary"
    model = "model"
    both = "both"


# `Expression.register` is a contract field name; shadowing ABCMeta.register on instances is harmless.
warnings.filterwarnings("ignore", message='Field name "register"', category=UserWarning)


class _Model(BaseModel):
    model_config = ConfigDict(use_enum_values=False)


class Health(_Model):
    status: str = "ok"
    version: str
    llm_provider: str


class Me(_Model):
    user_id: str


class AnnotationContext(_Model):
    surrounding: str | None = Field(default=None, max_length=2000)
    app: str | None = Field(default=None, max_length=100)


class AnnotateRequest(_Model):
    text: str = Field(min_length=1, max_length=2000)
    source_lang: SourceLang = SourceLang.auto
    target_lang: Lang
    depth: Depth = Depth.full
    context: AnnotationContext | None = None


class Token(_Model):
    text: str
    kind: TokenKind
    pinyin: str | None = None
    pinyin_numbered: str | None = None
    ipa: str | None = None
    gloss: str | None = None


class Expression(_Model):
    kind: ExpressionKind
    text: str
    token_start: int = Field(ge=0)
    token_end: int = Field(ge=1)
    pinyin: str | None = None
    literal: str | None = None
    meaning: str
    origin: str | None = None
    register: str | None = None
    equivalent: str | None = None
    confidence: float = Field(ge=0, le=1)
    source: ExpressionSource


class Annotation(_Model):
    id: str
    text: str
    source_lang: Lang
    target_lang: Lang
    depth: Depth
    translation: str
    tokens: list[Token]
    expressions: list[Expression]
    note: str | None = None
    cached: bool = False


class LineInput(_Model):
    id: str = Field(max_length=64)
    text: str = Field(min_length=1, max_length=500)


class AnnotateLinesRequest(_Model):
    lines: Annotated[list[LineInput], Field(min_length=1, max_length=50)]
    source_lang: SourceLang = SourceLang.auto
    target_lang: Lang
    context: AnnotationContext | None = None


class LineAnnotation(_Model):
    id: str
    text: str
    source_lang: Lang
    translation: str
    tokens: list[Token]
    expressions: list[Expression]


class AnnotateLinesResponse(_Model):
    target_lang: Lang
    lines: list[LineAnnotation]


class DocumentStatus(StrEnum):
    queued = "queued"
    processing = "processing"
    ready = "ready"
    failed = "failed"


class DocumentKind(StrEnum):
    pdf = "pdf"
    docx = "docx"
    txt = "txt"


class Document(_Model):
    id: UUID
    title: str
    kind: DocumentKind
    status: DocumentStatus
    progress: float = Field(ge=0, le=1)
    page_count: int | None = None
    size_bytes: int = 0
    source_lang: SourceLang
    target_lang: Lang
    error: str | None = None
    created_at: datetime
    updated_at: datetime


class DocumentList(_Model):
    items: list[Document]


BBox = Annotated[list[float], Field(min_length=4, max_length=4)]


class DocumentLine(_Model):
    id: str
    text: str
    bbox: BBox | None = None
    annotation: LineAnnotation


class DocumentBlock(_Model):
    id: str
    bbox: BBox | None = None
    lines: list[DocumentLine]


class DocumentPage(_Model):
    document_id: UUID
    index: int = Field(ge=0)
    width: float | None = None
    height: float | None = None
    blocks: list[DocumentBlock]


class FeedbackRequest(_Model):
    text: str = Field(min_length=1, max_length=2000)
    translation: str | None = Field(default=None, max_length=4000)
    target_lang: Lang
    comment: str | None = Field(default=None, max_length=1000)
