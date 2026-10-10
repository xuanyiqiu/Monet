"""Offline half of the golden set: the dictionary must find what each case says it should.

This runs in CI without any LLM. The model half (translation quality, false-positive rejection)
is scripts/eval_golden.py.
"""

from pathlib import Path

import pytest
import yaml

from monet_api.nlp.langdetect import detect

CASES = yaml.safe_load((Path(__file__).parent / "golden" / "cases.yaml").read_text(encoding="utf-8"))


def test_cases_are_well_formed():
    ids = [c["id"] for c in CASES]
    assert len(ids) == len(set(ids)) >= 40
    for c in CASES:
        assert c["target"] in {"en", "es", "zh"}, c["id"]
        assert set(c) <= {
            "id",
            "text",
            "target",
            "expect_dict",
            "expect_model",
            "forbid",
            "tr_any",
            "tr_none",
        }
        assert c.get("tr_any") or c.get("expect_dict"), f"{c['id']} asserts nothing"


@pytest.mark.parametrize("case", [c for c in CASES if c.get("expect_dict")], ids=lambda c: c["id"])
def test_dictionary_detects_expected_expressions(nlp, annotator, case):
    lang = detect(case["text"])
    analysis = annotator.analyze(case["text"], lang)
    found = [m.text.lower() for m in analysis.matches]
    for expected in case["expect_dict"]:
        assert any(expected.lower() in f or f in expected.lower() for f in found), (
            f"{case['id']}: dictionary missed {expected!r}; found {found}"
        )


@pytest.mark.parametrize("case", [c for c in CASES if c.get("forbid")], ids=lambda c: c["id"])
def test_single_char_traps_are_not_matched_by_dictionary(nlp, annotator, case):
    """Forbidden single Han characters (润 in 利润) must never even become candidates."""
    analysis = annotator.analyze(case["text"], detect(case["text"]))
    found = {m.text for m in analysis.matches}
    for bad in case["forbid"]:
        if len(bad) == 1 and not bad.isascii():
            assert bad not in found
