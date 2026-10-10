import pytest

from monet_api.nlp.langdetect import detect
from monet_api.nlp.phonetics import char_pinyin, join_range
from monet_api.nlp.text import normalize
from monet_api.nlp.tokenize import split_at, token_range
from monet_api.schemas import ExpressionKind, Lang, TokenKind


@pytest.mark.parametrize(
    ("text", "lang"),
    [
        ("这个视频绝绝子", Lang.zh),
        ("我emo了", Lang.zh),
        ("¿Qué tal estás? Estoy en las nubes", Lang.es),
        ("I'm under the weather today", Lang.en),
        ("La casa de mi madre", Lang.es),
        ("The house of my mother", Lang.en),
    ],
)
def test_detect(text, lang):
    assert detect(text) == lang


def test_detect_fallback_for_no_words():
    assert detect("12345", fallback=Lang.es) == Lang.es


def test_normalize_keeps_newlines_and_unifies_spaces():
    assert normalize("  你好　世界 \n  hola mundo ") == "你好 世界\nhola mundo"


@pytest.mark.parametrize(
    ("text", "lang"),
    [
        ("今天我真的破防了，太绝了！", Lang.zh),
        ("No tiene pelos en la lengua, ¿vale?", Lang.es),
        ("It's giving main character energy... 100%", Lang.en),
        ("我emo了 2333", Lang.zh),
    ],
)
def test_tokens_concatenate_to_input(nlp, text, lang):
    _, tok = nlp
    spans = tok.tokenize(text, lang)
    assert "".join(s.text for s in spans) == text
    assert all(spans[i].end == spans[i + 1].start for i in range(len(spans) - 1))


def test_lexicon_terms_stay_whole_in_segmentation(nlp):
    _, tok = nlp
    words = [s.text for s in tok.tokenize("他说话真是对牛弹琴，蚌埠住了", Lang.zh)]
    assert "对牛弹琴" in words and "蚌埠住了" in words


def test_split_at_and_token_range(nlp):
    _, tok = nlp
    text = "hello world"
    spans = split_at(tok.tokenize(text, Lang.en), [2, 8])
    assert "".join(s.text for s in spans) == text
    ts, te = token_range(spans, 2, 8)
    assert "".join(s.text for s in spans[ts:te]) == "llo wo"
    assert spans[0].kind == TokenKind.word


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("银行", "yín háng"),
        ("行走", "xíng zǒu"),
        ("你好", "nǐ hǎo"),
        ("音乐", "yīn yuè"),
        ("快乐", "kuài lè"),
    ],
)
def test_pinyin_uses_word_context(nlp, text, expected):
    _, tok = nlp
    spans = tok.tokenize(text, Lang.zh)
    marks, _ = char_pinyin(text, spans)
    assert join_range(marks, 0, len(text)) == expected


def test_pinyin_numbers_and_non_han(nlp):
    _, tok = nlp
    text = "i人的朋友"
    marks, nums = char_pinyin(text, tok.tokenize(text, Lang.zh))
    assert marks[0] is None
    assert join_range(nums, 0, len(text)) == "ren2 de5 peng2 you3"


def _terms(nlp, text, lang):
    lex, _ = nlp
    return [(m.text, m.entry.kind) for m in lex.find(text, lang)]


def test_lexicon_zh_memes_chengyu_and_xiehouyu(nlp):
    found = _terms(nlp, "家人们谁懂啊，老板画蛇添足，我直接蚌埠住了。外甥打灯笼——照舅", Lang.zh)
    texts = [t for t, _ in found]
    assert texts == ["家人们谁懂啊", "画蛇添足", "蚌埠住了", "外甥打灯笼——照舅"]
    assert dict(found)["外甥打灯笼——照舅"] == ExpressionKind.xiehouyu


def test_lexicon_xiehouyu_first_half_only(nlp):
    assert _terms(nlp, "这事儿啊，外甥打灯笼。", Lang.zh)[0][0] == "外甥打灯笼"


def test_lexicon_ascii_terms_need_boundaries(nlp):
    assert [t for t, _ in _terms(nlp, "yyds！666", Lang.zh)] == ["yyds", "666"]
    assert _terms(nlp, "价格是1666元", Lang.zh) == []


def test_lexicon_case_insensitive_variants(nlp):
    assert _terms(nlp, "真的YYDS", Lang.zh)[0][0] == "YYDS"


def test_lexicon_spanish_conjugations(nlp):
    found = [t for t, _ in _terms(nlp, "Hoy está en las nubes y me tomas el pelo. ¡Qué fuerte!", Lang.es)]
    assert found == ["está en las nubes", "tomas el pelo", "Qué fuerte"]


def test_lexicon_english_inflections(nlp):
    found = [t for t, _ in _terms(nlp, "She spilled the tea, no cap. Go touch grass.", Lang.en)]
    assert found == ["spilled the tea", "no cap", "touch grass"]


def test_lexicon_mixed_zh_with_english_slang(nlp):
    found = [t for t, _ in _terms(nlp, "这个真的no cap，绝绝子", Lang.zh)]
    assert found == ["no cap", "绝绝子"]
