"""Pinyin per character, computed with word-level context (so 银行 → yín háng, 行走 → xíng zǒu)."""

from pypinyin import Style, lazy_pinyin

from monet_api.nlp.text import is_han
from monet_api.nlp.tokenize import Span


def char_pinyin(text: str, spans: list[Span]) -> tuple[list[str | None], list[str | None]]:
    """Return (tone-mark, tone-number) syllables per char index; None for non-Han chars."""
    marks: list[str | None] = [None] * len(text)
    nums: list[str | None] = [None] * len(text)
    for s in spans:
        idx = [s.start + i for i, c in enumerate(s.text) if is_han(c)]
        if not idx:
            continue
        han = "".join(text[i] for i in idx)
        m = lazy_pinyin(han, style=Style.TONE)
        n = lazy_pinyin(han, style=Style.TONE3, neutral_tone_with_five=True)
        if len(m) != len(idx) or len(n) != len(idx):  # defensive: pypinyin groups unknown chars
            m = [lazy_pinyin(text[i], style=Style.TONE)[0] for i in idx]
            n = [lazy_pinyin(text[i], style=Style.TONE3, neutral_tone_with_five=True)[0] for i in idx]
        for i, a, b in zip(idx, m, n, strict=True):
            marks[i], nums[i] = a, b
    return marks, nums


def join_range(syllables: list[str | None], start: int, end: int) -> str | None:
    parts = [p for p in syllables[start:end] if p]
    return " ".join(parts) if parts else None
