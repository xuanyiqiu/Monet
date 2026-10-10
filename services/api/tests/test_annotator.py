import json

from monet_api.annotator import Annotator
from monet_api.cache import MemoryCache
from monet_api.llm.base import LLMError
from monet_api.llm.fake import FakeProvider
from monet_api.schemas import (
    AnnotateLinesRequest,
    AnnotateRequest,
    Depth,
    ExpressionKind,
    ExpressionSource,
    Lang,
)


def _span_text(ann, e):
    return "".join(t.text for t in ann.tokens[e.token_start : e.token_end])


async def test_full_annotation_zh(annotator):
    ann = await annotator.annotate(AnnotateRequest(text="这家店绝绝子，老板却画蛇添足", target_lang=Lang.en))
    assert ann.source_lang == Lang.zh
    assert ann.translation.startswith("[en]")
    assert "".join(t.text for t in ann.tokens) == ann.text
    kinds = {e.text: e for e in ann.expressions}
    assert kinds["绝绝子"].kind == ExpressionKind.meme
    assert kinds["画蛇添足"].kind == ExpressionKind.chengyu
    assert kinds["画蛇添足"].pinyin == "huà shé tiān zú"
    assert all(e.source == ExpressionSource.both for e in ann.expressions)
    for e in ann.expressions:
        assert _span_text(ann, e) == e.text
    shop = next(t for t in ann.tokens if t.text == "店")
    assert shop.pinyin == "diàn" and shop.pinyin_numbered == "dian4" and shop.gloss == "<店>"


async def test_cache_hit_skips_llm(annotator, fake_llm):
    req = AnnotateRequest(text="躺平了", target_lang=Lang.es)
    first = await annotator.annotate(req)
    second = await annotator.annotate(req)
    assert fake_llm.calls == 1
    assert not first.cached and second.cached
    assert first.id == second.id


async def test_degraded_when_llm_fails(nlp):
    lex, tok = nlp
    a = Annotator(lex, tok, FakeProvider(fail=True), MemoryCache())
    ann = await a.annotate(AnnotateRequest(text="我真的破防了", target_lang=Lang.es))
    assert ann.translation == ""
    assert ann.note and "diccionario" in ann.note
    assert [e.text for e in ann.expressions] == ["破防"]
    assert ann.expressions[0].source == ExpressionSource.dictionary
    assert any(t.pinyin == "pò fáng" for t in ann.tokens)


class ScriptedLLM:
    """Returns a fixed reply, to test merge rules."""

    name = "scripted"
    model = "scripted-1"

    def __init__(self, reply: dict) -> None:
        self.reply = reply
        self.last_payload: dict | None = None

    async def complete_json(self, system: str, user: str) -> dict:
        self.last_payload = json.loads(user)
        if self.reply is None:
            raise LLMError("x")
        return self.reply


async def test_model_rejects_ambiguous_candidate_and_adds_its_own(nlp):
    lex, tok = nlp
    llm = ScriptedLLM(
        {
            "translation": "This costs 666 yuan, what a rip-off",
            "words": [],
            "expressions": [
                {"text": "宰人", "kind": "slang", "meaning": "to rip off customers", "confidence": 0.8}
            ],
        }
    )
    a = Annotator(lex, tok, llm, MemoryCache())
    ann = await a.annotate(AnnotateRequest(text="这个卖666元，太宰人了", target_lang=Lang.en))
    assert llm.last_payload["candidates"] == []  # "666元" is a price: never offered as slang
    assert [e.text for e in ann.expressions] == ["宰人"]
    e = ann.expressions[0]
    assert e.source == ExpressionSource.model and e.confidence <= 0.85
    assert _span_text(ann, e) == "宰人"


async def test_model_expression_not_in_text_is_dropped(nlp):
    lex, tok = nlp
    llm = ScriptedLLM(
        {
            "translation": "hi",
            "words": [],
            "expressions": [{"text": "不存在", "kind": "meme", "meaning": "x"}],
        }
    )
    ann = await Annotator(lex, tok, llm, MemoryCache()).annotate(
        AnnotateRequest(text="你好", target_lang=Lang.en)
    )
    assert ann.expressions == []


async def test_nonambiguous_dictionary_hit_kept_even_if_model_omits(nlp):
    lex, tok = nlp
    llm = ScriptedLLM({"translation": "He played the lute to a cow", "words": [], "expressions": []})
    ann = await Annotator(lex, tok, llm, MemoryCache()).annotate(
        AnnotateRequest(text="他对牛弹琴", target_lang=Lang.en)
    )
    assert [(e.text, e.source) for e in ann.expressions] == [("对牛弹琴", ExpressionSource.dictionary)]


async def test_single_char_term_needs_token_alignment(annotator):
    ann = await annotator.annotate(AnnotateRequest(text="公司利润很高", target_lang=Lang.en))
    assert ann.expressions == []


async def test_lines_batch_one_llm_call_and_per_line_cache(annotator, fake_llm):
    req = AnnotateLinesRequest(
        lines=[
            {"id": "a", "text": "今天又内卷了"},
            {"id": "b", "text": "我选择躺平"},
            {"id": "c", "text": "666"},
        ],
        target_lang=Lang.es,
    )
    res = await annotator.annotate_lines(req)
    assert fake_llm.calls == 1
    assert [ln.id for ln in res.lines] == ["a", "b", "c"]
    assert res.lines[0].translation == "[es] 今天又内卷了"
    assert res.lines[1].expressions[0].text == "躺平"
    assert res.lines[2].source_lang == Lang.zh  # numbers inherit the screen's language
    again = await annotator.annotate_lines(req)
    assert fake_llm.calls == 1
    assert again.lines[1].translation == res.lines[1].translation


async def test_lines_chunking(annotator, fake_llm):
    lines = [{"id": str(i), "text": f"第{i}行"} for i in range(45)]
    await annotator.annotate_lines(AnnotateLinesRequest(lines=lines, target_lang=Lang.en), chunk_size=20)
    assert fake_llm.calls == 3


async def test_fast_depth_via_annotate(annotator):
    ann = await annotator.annotate(AnnotateRequest(text="yyds", target_lang=Lang.en, depth=Depth.fast))
    assert ann.depth == Depth.fast
    assert ann.expressions[0].origin is None  # fast mode omits long explanations
