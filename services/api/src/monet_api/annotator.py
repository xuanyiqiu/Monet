"""Annotation pipeline: normalize → detect language → tokenize → pinyin → lexicon → LLM → merge.

Dictionary facts ground the model; the model confirms candidates in context, adds what the lexicons
missed and writes everything in the target language. If the model fails we still return tokens, pinyin
and dictionary hits (degraded mode) instead of an error.
"""

import json
import logging
from dataclasses import dataclass

from monet_api.cache import Cache, cache_key
from monet_api.llm.base import LLMError, LLMProvider
from monet_api.llm.prompts import PROMPT_VERSION, SYSTEM_FULL, SYSTEM_LINES, full_payload, lines_payload
from monet_api.nlp.langdetect import detect
from monet_api.nlp.lexicon import Lexicon, Match, is_quantity
from monet_api.nlp.phonetics import char_pinyin, join_range
from monet_api.nlp.text import has_han, normalize
from monet_api.nlp.tokenize import Span, Tokenizer, split_at, token_range
from monet_api.schemas import (
    AnnotateLinesRequest,
    AnnotateLinesResponse,
    AnnotateRequest,
    Annotation,
    Depth,
    Expression,
    ExpressionKind,
    ExpressionSource,
    Lang,
    LineAnnotation,
    SourceLang,
    Token,
    TokenKind,
)

log = logging.getLogger(__name__)

DEGRADED_NOTE = {
    Lang.zh: "翻译服务暂时不可用，目前只显示词典结果。",
    Lang.es: "La traducción no está disponible ahora mismo; se muestran solo resultados del diccionario.",
    Lang.en: "Translation is temporarily unavailable; showing dictionary results only.",
}
_GLOSSED = (TokenKind.word, TokenKind.number)


class EmptyTextError(ValueError):
    pass


@dataclass(slots=True)
class Analysis:
    text: str
    lang: Lang
    spans: list[Span]
    matches: list[Match]
    marks: list[str | None]
    nums: list[str | None]


@dataclass(slots=True)
class _Item:
    start: int
    end: int
    kind: ExpressionKind
    meaning: str
    confidence: float
    source: ExpressionSource
    pinyin: str | None = None
    literal: str | None = None
    origin: str | None = None
    register: str | None = None
    equivalent: str | None = None


def _str(v) -> str | None:
    return v.strip() if isinstance(v, str) and v.strip() else None


def _kind(v) -> ExpressionKind:
    try:
        return ExpressionKind(v)
    except ValueError:
        return ExpressionKind.other


def _conf(v, default: float) -> float:
    try:
        return max(0.0, min(1.0, float(v)))
    except (TypeError, ValueError):
        return default


class Annotator:
    def __init__(self, lexicon: Lexicon, tokenizer: Tokenizer, llm: LLMProvider, cache: Cache) -> None:
        self.lexicon = lexicon
        self.tokenizer = tokenizer
        self.llm = llm
        self.cache = cache

    # ---------------------------------------------------------------- analysis (deterministic)

    def analyze(self, text: str, lang: Lang) -> Analysis:
        spans = self.tokenizer.tokenize(text, lang)
        by_start = {s.start: s for s in spans}
        matches = [
            m
            for m in self.lexicon.find(text, lang)
            if not is_quantity(text, m.start, m.end)
            # single-character Chinese terms (卷, 润) only count when the segmenter isolates them
            if not (m.end - m.start == 1 and has_han(m.text))
            or (m.start in by_start and by_start[m.start].end == m.end)
        ]
        if has_han(text):
            marks, nums = char_pinyin(text, spans)
        else:
            marks = nums = [None] * len(text)
        return Analysis(text, lang, spans, matches, marks, nums)

    @staticmethod
    def _candidates(a: Analysis) -> list[dict]:
        return [{"text": m.text, **m.entry.facts()} for m in a.matches]

    # ---------------------------------------------------------------- LLM with cache

    async def _call(self, key: str, system: str, payload: str) -> tuple[dict | None, bool]:
        """Returns (model output or None if failed, cached?)."""
        if (hit := await self.cache.get(key)) is not None:
            return hit, True
        try:
            out = await self.llm.complete_json(system, payload)
        except LLMError as e:
            log.warning("llm failed: %s", e)
            return None, False
        return out, False

    # ---------------------------------------------------------------- merge

    def _build(
        self, a: Analysis, tgt: Lang, model_exprs: list, words: list, failed: bool, depth: Depth
    ) -> tuple[list[Token], list[Expression]]:
        model = [e for e in model_exprs if isinstance(e, dict) and _str(e.get("text"))]
        used: set[int] = set()
        items: list[_Item] = []

        for m in a.matches:
            low = m.text.lower()
            mi = next(
                (
                    i
                    for i, e in enumerate(model)
                    if i not in used and (e["text"].lower() in low or low in e["text"].lower())
                ),
                None,
            )
            entry = m.entry
            base = _Item(
                m.start,
                m.end,
                entry.kind,
                entry.meaning,
                confidence=0.5 if entry.ambiguous else 0.85,
                source=ExpressionSource.dictionary,
                pinyin=entry.pinyin or join_range(a.marks, m.start, m.end),
                literal=entry.literal,
                origin=entry.origin,
                register=entry.register,
                equivalent=entry.equivalent.get(tgt.value),
            )
            if mi is not None:
                used.add(mi)
                e = model[mi]
                base.meaning = _str(e.get("meaning")) or base.meaning
                base.literal = _str(e.get("literal")) or base.literal
                base.origin = _str(e.get("origin")) or base.origin
                base.register = _str(e.get("register")) or base.register
                base.equivalent = _str(e.get("equivalent")) or base.equivalent
                base.confidence = max(_conf(e.get("confidence"), 0.8), 0.5 if entry.ambiguous else 0.9)
                base.source = ExpressionSource.both
            elif not failed and entry.ambiguous:
                continue  # the model looked at the context and rejected this reading
            items.append(base)

        for i, e in enumerate(model):
            if i in used:
                continue
            start = a.text.lower().find(e["text"].lower())
            if start < 0:
                continue
            end = start + len(e["text"])
            if is_quantity(a.text, start, end):
                continue  # the model mistook a price/quantity for slang
            if any(not (end <= it.start or start >= it.end) for it in items):
                continue
            items.append(
                _Item(
                    start,
                    end,
                    _kind(e.get("kind")),
                    _str(e.get("meaning")) or "",
                    confidence=min(_conf(e.get("confidence"), 0.6), 0.85),
                    source=ExpressionSource.model,
                    pinyin=join_range(a.marks, start, end),
                    literal=_str(e.get("literal")),
                    origin=_str(e.get("origin")),
                    register=_str(e.get("register")),
                    equivalent=_str(e.get("equivalent")),
                )
            )

        spans = split_at(a.spans, [b for it in items for b in (it.start, it.end)])
        glosses = [w for w in words if isinstance(w, dict) and isinstance(w.get("text"), str)]
        j = 0
        tokens: list[Token] = []
        for s in spans:
            gloss = ipa = None
            if s.kind in _GLOSSED:
                for k in range(j, min(j + 3, len(glosses))):
                    if glosses[k]["text"] == s.text:
                        gloss, ipa = _str(glosses[k].get("gloss")), _str(glosses[k].get("ipa"))
                        j = k + 1
                        break
            tokens.append(
                Token(
                    text=s.text,
                    kind=s.kind,
                    pinyin=join_range(a.marks, s.start, s.end),
                    pinyin_numbered=join_range(a.nums, s.start, s.end),
                    gloss=gloss,
                    ipa=ipa,
                )
            )

        exprs = []
        for it in sorted(items, key=lambda it: it.start):
            ts, te = token_range(spans, it.start, it.end)
            full = depth == Depth.full
            exprs.append(
                Expression(
                    kind=it.kind,
                    text=a.text[it.start : it.end],
                    token_start=ts,
                    token_end=te,
                    pinyin=it.pinyin,
                    meaning=it.meaning,
                    confidence=round(it.confidence, 2),
                    source=it.source,
                    literal=it.literal if full else None,
                    origin=it.origin if full else None,
                    register=it.register if full else None,
                    equivalent=it.equivalent,
                )
            )
        return tokens, exprs

    # ---------------------------------------------------------------- public API

    async def annotate(self, req: AnnotateRequest) -> Annotation:
        text = normalize(req.text)
        if not text:
            raise EmptyTextError("text is empty after normalization")
        tgt = req.target_lang
        if req.depth == Depth.fast:
            lines = await self.annotate_lines(
                AnnotateLinesRequest(
                    lines=[{"id": "0", "text": text[:500]}],
                    source_lang=req.source_lang,
                    target_lang=tgt,
                    context=req.context,
                )
            )
            ln = lines.lines[0]
            key = cache_key(PROMPT_VERSION, self.llm.model, "fast", ln.source_lang, tgt, ln.text)
            return Annotation(
                id=key,
                text=ln.text,
                source_lang=ln.source_lang,
                target_lang=tgt,
                depth=Depth.fast,
                translation=ln.translation,
                tokens=ln.tokens,
                expressions=ln.expressions,
                note=None if ln.translation else DEGRADED_NOTE[tgt],
                cached=False,
            )

        lang = detect(text) if req.source_lang == SourceLang.auto else Lang(req.source_lang.value)
        a = self.analyze(text, lang)
        ctx = req.context.model_dump(exclude_none=True) if req.context else None
        key = cache_key(
            PROMPT_VERSION,
            self.llm.model,
            "full",
            lang,
            tgt,
            text,
            json.dumps(ctx or {}, sort_keys=True, ensure_ascii=False),
        )
        payload = full_payload(
            text,
            lang.value,
            tgt.value,
            [s.text for s in a.spans if s.kind in _GLOSSED],
            self._candidates(a),
            ctx,
        )
        out, cached = await self._call(key, SYSTEM_FULL, payload)
        failed = out is None or not isinstance(out.get("translation"), str)
        if not failed and not cached:
            await self.cache.set(key, out)
        out = out or {}
        tokens, exprs = self._build(
            a, tgt, out.get("expressions") or [], out.get("words") or [], failed, Depth.full
        )
        return Annotation(
            id=key,
            text=text,
            source_lang=lang,
            target_lang=tgt,
            depth=Depth.full,
            translation="" if failed else out["translation"].strip(),
            tokens=tokens,
            expressions=exprs,
            note=DEGRADED_NOTE[tgt] if failed else _str(out.get("note")),
            cached=cached,
        )

    async def annotate_lines(self, req: AnnotateLinesRequest, chunk_size: int = 20) -> AnnotateLinesResponse:
        tgt = req.target_lang
        texts = [normalize(line.text) or line.text for line in req.lines]
        if req.source_lang == SourceLang.auto:
            overall = detect(" ".join(texts))
            langs = [detect(t, fallback=overall) for t in texts]
        else:
            langs = [Lang(req.source_lang.value)] * len(texts)
        analyses = [self.analyze(t, lg) for t, lg in zip(texts, langs, strict=True)]
        keys = [
            cache_key(PROMPT_VERSION, self.llm.model, "fast", lg, tgt, t)
            for t, lg in zip(texts, langs, strict=True)
        ]

        outs: list[dict | None] = [await self.cache.get(k) for k in keys]
        misses = [i for i, o in enumerate(outs) if o is None]
        ctx = req.context.model_dump(exclude_none=True) if req.context else {}
        ctx["screen"] = "\n".join(texts)[:2000]
        for c in range(0, len(misses), chunk_size):
            chunk = misses[c : c + chunk_size]
            payload = lines_payload(
                [
                    {"id": str(i), "text": texts[i], "candidates": self._candidates(analyses[i])}
                    for i in chunk
                ],
                langs[chunk[0]].value,
                tgt.value,
                ctx,
            )
            try:
                out = await self.llm.complete_json(SYSTEM_LINES, payload)
            except LLMError as e:
                log.warning("llm failed for %d lines: %s", len(chunk), e)
                continue
            by_id = {str(x.get("id")): x for x in out.get("lines", []) if isinstance(x, dict)}
            for i in chunk:
                got = by_id.get(str(i))
                if got and isinstance(got.get("translation"), str):
                    outs[i] = got
                    await self.cache.set(keys[i], got)

        result = []
        for i, line in enumerate(req.lines):
            o = outs[i]
            tokens, exprs = self._build(
                analyses[i], tgt, (o or {}).get("expressions") or [], [], o is None, Depth.fast
            )
            result.append(
                LineAnnotation(
                    id=line.id,
                    text=texts[i],
                    source_lang=langs[i],
                    translation=(o or {}).get("translation", "").strip(),
                    tokens=tokens,
                    expressions=exprs,
                )
            )
        return AnnotateLinesResponse(target_lang=tgt, lines=result)
