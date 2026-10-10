"""Curated expression lexicons (memes, slang, idioms, 成语, 歇后语) and a fast matcher.

Lexicon files live in data/lexicon/*.json (hand-curated) plus data/generated/chengyu.json
(built by scripts/fetch_data.py from the chinese-xinhua dataset). See data/lexicon/README.md.
"""

import json
import logging
import re
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

from monet_api.nlp.text import has_han
from monet_api.schemas import ExpressionKind, Lang

log = logging.getLogger(__name__)

_XHY_SEPARATORS = ("——", "—", "--", "，", ",", "")
# Latin-only word boundary: Python's \b treats Han characters as word chars, so "的no cap" would not match.
_L = "0-9A-Za-zÀ-ÖØ-öø-ÿ"
_BOUNDARY = f"(?:(?<![{_L}])(?=[{_L}])|(?<=[{_L}])(?![{_L}]))"


def _latin_pattern(phrase: str) -> str:
    return r"\b" + r"\s+".join(map(re.escape, phrase.split())) + r"\b"


@dataclass(frozen=True, slots=True)
class LexEntry:
    term: str
    lang: Lang
    kind: ExpressionKind
    meaning: str
    notes_lang: str = "en"  # language the meaning/origin/... strings are written in
    pinyin: str | None = None
    literal: str | None = None
    origin: str | None = None
    register: str | None = None
    equivalent: dict[str, str] = field(default_factory=dict)
    ambiguous: bool = False
    variants: tuple[str, ...] = ()
    regex: str | None = None

    def facts(self) -> dict:
        """Compact facts sent to the LLM as grounding."""
        d = {
            "kind": self.kind.value,
            "meaning": self.meaning,
            "literal": self.literal,
            "origin": self.origin,
            "register": self.register,
            "ambiguous": self.ambiguous or None,
        }
        return {k: v for k, v in d.items() if v}


@dataclass(frozen=True, slots=True)
class Match:
    entry: LexEntry
    start: int
    end: int
    text: str


_UNITS_AFTER = re.compile(
    r"^\s*(?:元|块|块钱|毛|角|分钟|小时|个|只|条|次|年|月|号|日|岁|斤|公斤|米|公里|人民币|rmb|usd|yuan|dollars?|euros?|%|％)",
    re.I,
)
_CONTEXT_BEFORE = re.compile(
    r"(?:一共|共计|总共|共|约|大约|售价|价格|价|花了|花|付了|付|收了|收|[￥¥$€])\s*$"
)


def is_quantity(text: str, start: int, end: int) -> bool:
    """True if an all-digit span is used as a plain quantity or price (666元, 一共666, ¥520), not as slang."""
    span = text[start:end]
    if not span.isascii() or not span.isdigit():
        return False
    return bool(_UNITS_AFTER.match(text[end:]) or _CONTEXT_BEFORE.search(text[:start]))


def _is_ascii_alnum(ch: str) -> bool:
    return ch.isascii() and ch.isalnum()


class Lexicon:
    def __init__(self, entries: list[LexEntry]) -> None:
        self.entries = entries
        # zh: first-char index for forward maximum matching (case-insensitive)
        self._zh: dict[str, list[tuple[str, LexEntry]]] = defaultdict(list)
        self._latin: dict[Lang, list[tuple[re.Pattern[str], LexEntry]]] = defaultdict(list)
        seen: set[tuple[str, str]] = set()
        for e in entries:
            if e.lang == Lang.zh:
                for form in (e.term, *e.variants):
                    key = (form.lower(), e.lang.value)
                    if key in seen:
                        continue
                    seen.add(key)
                    self._zh[form[0].lower()].append((form.lower(), e))
            else:
                pattern = "|".join([e.regex or _latin_pattern(e.term), *map(_latin_pattern, e.variants)])
                pattern = pattern.replace(r"\b", _BOUNDARY)
                self._latin[e.lang].append((re.compile(pattern, re.IGNORECASE), e))
        for forms in self._zh.values():
            forms.sort(key=lambda f: len(f[0]), reverse=True)

    def __len__(self) -> int:
        return len(self.entries)

    def words(self) -> list[str]:
        """All Chinese surface forms, for registering in the segmenter."""
        return [form for forms in self._zh.values() for form, _ in forms]

    def find(self, text: str, lang: Lang) -> list[Match]:
        if lang == Lang.zh or has_han(text):
            matches = self._find_zh(text)
            if lang != Lang.zh:
                return matches
            # Mixed text (e.g. "我emo了", "no cap 绝绝子"): also look for latin slang.
            latin = [m for lg in (Lang.en, Lang.es) for m in self._find_latin(text, lg)]
            return _resolve_overlaps(matches + latin)
        # Chinese internet slang is often typed in Latin letters/digits (yyds, 520) inside any language.
        zh_ascii = [m for m in self._find_zh(text) if not m.entry.ambiguous]
        return _resolve_overlaps(self._find_latin(text, lang) + zh_ascii)

    def _find_zh(self, text: str) -> list[Match]:
        low = text.lower()
        out: list[Match] = []
        i = 0
        while i < len(text):
            hit = None
            for form, entry in self._zh.get(low[i], ()):
                j = i + len(form)
                if low.startswith(form, i):
                    # ascii terms (yyds, 666, emo) must not be part of a longer ascii word/number
                    if _is_ascii_alnum(form[0]) and i > 0 and _is_ascii_alnum(text[i - 1]):
                        continue
                    if _is_ascii_alnum(form[-1]) and j < len(text) and _is_ascii_alnum(text[j]):
                        continue
                    hit = Match(entry, i, j, text[i:j])
                    break
            if hit:
                out.append(hit)
                i = hit.end
            else:
                i += 1
        return out

    def _find_latin(self, text: str, lang: Lang) -> list[Match]:
        found = [
            Match(e, m.start(), m.end(), m.group(0))
            for rx, e in self._latin.get(lang, ())
            for m in rx.finditer(text)
            if m.end() > m.start()
        ]
        return _resolve_overlaps(found)


def _resolve_overlaps(matches: list[Match]) -> list[Match]:
    """Keep longest matches first, then leftmost; drop anything overlapping a kept match."""
    kept: list[Match] = []
    for m in sorted(matches, key=lambda m: (-(m.end - m.start), m.start)):
        if all(m.end <= k.start or m.start >= k.end for k in kept):
            kept.append(m)
    return sorted(kept, key=lambda m: m.start)


def _entry_from_json(d: dict) -> list[LexEntry]:
    kind = ExpressionKind(d["kind"])
    variants = list(d.get("variants", []))
    if kind == ExpressionKind.xiehouyu and "——" in d["term"]:
        head, tail = d["term"].split("——", 1)
        variants += [head + sep + tail for sep in _XHY_SEPARATORS] + [head]
    return [
        LexEntry(
            term=d["term"],
            lang=Lang(d["lang"]),
            kind=kind,
            meaning=d["meaning"],
            notes_lang=d.get("notes_lang", "en"),
            pinyin=d.get("pinyin"),
            literal=d.get("literal"),
            origin=d.get("origin"),
            register=d.get("register"),
            equivalent=d.get("equivalent", {}),
            ambiguous=d.get("ambiguous", False),
            variants=tuple(v for v in dict.fromkeys(variants) if v and v != d["term"]),
            regex=d.get("regex"),
        )
    ]


def load_lexicon(lexicon_dir: Path, chengyu_path: Path | None = None) -> Lexicon:
    entries: list[LexEntry] = []
    for path in sorted(lexicon_dir.glob("*.json")):
        for d in json.loads(path.read_text(encoding="utf-8")):
            entries.extend(_entry_from_json(d))
    curated = len(entries)
    if chengyu_path and chengyu_path.exists():
        curated_terms = {e.term for e in entries}
        for d in json.loads(chengyu_path.read_text(encoding="utf-8")):
            if d["word"] in curated_terms:
                continue
            entries.append(
                LexEntry(
                    term=d["word"],
                    lang=Lang.zh,
                    kind=ExpressionKind.chengyu if len(d["word"]) == 4 else ExpressionKind.proverb,
                    meaning=d["explanation"],
                    notes_lang="zh",
                    pinyin=d.get("pinyin"),
                    origin=d.get("derivation") or None,
                )
            )
    else:
        log.warning("chengyu lexicon missing (%s); run scripts/fetch_data.py", chengyu_path)
    log.info("lexicon: %d curated + %d generated entries", curated, len(entries) - curated)
    return Lexicon(entries)
