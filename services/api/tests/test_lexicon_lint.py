import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))
from lint_lexicon import LEX_DIR, lint  # noqa: E402


def write(tmp_path, entries):
    (tmp_path / "t.json").write_text(json.dumps(entries, ensure_ascii=False), encoding="utf-8")
    return lint(tmp_path)


def good(**over):
    base = {"term": "测试", "lang": "zh", "kind": "slang", "meaning": "a test",
            "equivalent": {"en": "test", "es": "prueba"}}  # fmt: skip
    return {**base, **over}


def test_real_lexicons_are_clean():
    assert lint(LEX_DIR) == []


def test_linter_accepts_valid_entry(tmp_path):
    assert write(tmp_path, [good()]) == []


def test_linter_catches_problems(tmp_path):
    problems = write(
        tmp_path,
        [
            good(term="", meaning=""),
            good(term="a", kind="bogus"),
            good(term="520", equivalent={"en": "x", "es": "y"}),  # digit slang not flagged ambiguous
            good(term="重复", equivalent={"en": "x"}),  # missing es
            good(
                term="重复", equivalent={"en": "x", "es": "y"}, variants=["别名"]
            ),  # duplicate term? same term ok
            good(term="另一个", variants=["别名"]),
            good(term="x", lang="en", equivalent={"en": "same", "es": "a", "zh": "b"}),
            good(term="y", lang="en", regex="(unclosed", equivalent={"es": "a", "zh": "b"}),
            good(term="歇后", kind="xiehouyu"),
            good(term="长", meaning="x" * 300),
            good(term="多余", extra_field=1),
        ],
    )
    text = "\n".join(problems)
    for expected in ("missing term", "bad lang/kind", "must be flagged ambiguous", "missing equivalent",
                     "already defined", "own language", "bad regex", "must contain ——", "too long",
                     "unknown fields"):  # fmt: skip
        assert expected in text, f"linter missed: {expected}\n{text}"
