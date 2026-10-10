"""Tokenization into spans that concatenate back to the exact input text.

Chinese uses jieba (with every lexicon term registered so idioms/memes stay whole);
Spanish/English use a regex word splitter.
"""

import logging
import re
from collections.abc import Iterable
from dataclasses import dataclass

import jieba

from monet_api.nlp.text import has_han, is_punct
from monet_api.schemas import Lang, TokenKind

log = logging.getLogger(__name__)
jieba.setLogLevel(logging.WARNING)

_LATIN_TOKEN = re.compile(r"\s+|\d+(?:[.,]\d+)*|[^\W\d_]+(?:['’][^\W\d_]+)*|[^\w\s]|_", re.UNICODE)


@dataclass(frozen=True, slots=True)
class Span:
    start: int
    end: int
    text: str
    kind: TokenKind


def _kind(tok: str) -> TokenKind:
    if tok.isspace():
        return TokenKind.space
    if all(is_punct(c) for c in tok):
        return TokenKind.punct
    if tok.isdigit() or re.fullmatch(r"\d+(?:[.,]\d+)*", tok):
        return TokenKind.number
    if any(c.isalpha() for c in tok):
        return TokenKind.word
    return TokenKind.symbol


class Tokenizer:
    def __init__(self, extra_words: Iterable[str] = ()) -> None:
        self._jieba = jieba.Tokenizer()
        self._jieba.initialize()
        n = 0
        for w in extra_words:
            if has_han(w) and " " not in w:
                self._jieba.add_word(w, freq=200_000)
                n += 1
        log.info("jieba ready with %d extra words", n)

    def tokenize(self, text: str, lang: Lang) -> list[Span]:
        if lang == Lang.zh:
            pieces = list(self._jieba.cut(text, HMM=True))
        else:
            pieces = _LATIN_TOKEN.findall(text)
        spans: list[Span] = []
        pos = 0
        for p in pieces:
            if not p:
                continue
            # Regex splitters may skip characters; never lose any.
            idx = text.find(p, pos)
            if idx > pos:
                gap = text[pos:idx]
                spans.append(Span(pos, idx, gap, _kind(gap)))
            spans.append(Span(idx, idx + len(p), p, _kind(p)))
            pos = idx + len(p)
        if pos < len(text):
            rest = text[pos:]
            spans.append(Span(pos, len(text), rest, _kind(rest)))
        return spans


def split_at(spans: list[Span], boundaries: Iterable[int]) -> list[Span]:
    """Split spans so that every char offset in `boundaries` falls on a span edge."""
    cuts = sorted(set(boundaries))
    out: list[Span] = []
    for s in spans:
        inner = [b for b in cuts if s.start < b < s.end]
        if not inner:
            out.append(s)
            continue
        edges = [s.start, *inner, s.end]
        for a, b in zip(edges, edges[1:], strict=False):
            piece = s.text[a - s.start : b - s.start]
            out.append(Span(a, b, piece, _kind(piece)))
    return out


def token_range(spans: list[Span], start: int, end: int) -> tuple[int, int]:
    """Map a char range (already aligned via split_at) to [token_start, token_end)."""
    ts = next(i for i, s in enumerate(spans) if s.start == start)
    te = next(i for i, s in enumerate(spans) if s.end == end) + 1
    return ts, te
