import json
from pathlib import Path

PATH = Path(__file__).parents[1] / "data" / "lexicon" / "memes.zh.json"

TERMS = [
    "孔乙己文学",
    "脆皮大学生",
    "偷感",
    "淡人",
    "多巴胺穿搭",
    "嘴替",
    "反内卷",
    "科目三",
    "泰酷辣",
    "格局打开",
    "命运的齿轮开始转动",
    "city不city",
]

UNCLEAR_ORIGINS = {
    "偷感": "2024",
    "泰酷辣": "2023",
    "格局打开": "2022",
    "命运的齿轮开始转动": "2022",
    "city不city": "2023",
}


def load():
    return json.loads(PATH.read_text(encoding="utf-8"))


def test_terms_present_exactly_once():
    entries = load()
    for term in TERMS:
        assert sum(1 for e in entries if e["term"] == term) == 1, term


def test_entries_have_required_fields():
    by_term = {e["term"]: e for e in load()}
    for term in TERMS:
        entry = by_term[term]
        for field in ("pinyin", "literal", "meaning", "origin"):
            assert entry.get(field), f"{term} missing {field}"
        assert entry["equivalent"].get("en"), f"{term} missing equivalent.en"
        assert entry["equivalent"].get("es"), f"{term} missing equivalent.es"


def test_unclear_origins():
    by_term = {e["term"]: e for e in load()}
    for term, year in UNCLEAR_ORIGINS.items():
        expected = f"Origin unclear; spread on Chinese social media around {year}."
        assert by_term[term]["origin"] == expected, term


def test_file_size_and_unique_terms():
    entries = load()
    assert len(entries) == 121
    terms = [e["term"] for e in entries]
    assert len(terms) == len(set(terms))
