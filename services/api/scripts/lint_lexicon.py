"""Validate the curated lexicons in data/lexicon/*.json. Exit code 1 on any error (runs in CI via pytest).

uv run python scripts/lint_lexicon.py
"""

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

LEX_DIR = Path(__file__).resolve().parents[1] / "data" / "lexicon"
KINDS = {"chengyu", "idiom", "proverb", "xiehouyu", "meme", "slang", "wordplay", "classical", "other"}
LANGS = {"zh", "es", "en"}
KNOWN = {"term", "lang", "kind", "meaning", "pinyin", "literal", "origin", "register", "equivalent",
         "ambiguous", "variants", "regex", "notes_lang"}  # fmt: skip
HAN = re.compile(r"[一-鿿]")


def lint(lex_dir: Path = LEX_DIR) -> list[str]:
    errors: list[str] = []
    seen: dict[tuple[str, str], str] = {}
    for path in sorted(lex_dir.glob("*.json")):
        entries = json.loads(path.read_text(encoding="utf-8"))
        for i, e in enumerate(entries):
            where = f"{path.name}[{i}] {e.get('term', '?')!r}"

            def err(msg: str, where: str = where) -> None:
                errors.append(f"{where}: {msg}")

            for f in ("term", "lang", "kind", "meaning"):
                if not e.get(f):
                    err(f"missing {f}")
            if unknown := set(e) - KNOWN:
                err(f"unknown fields {sorted(unknown)}")
            if e.get("lang") not in LANGS or e.get("kind") not in KINDS:
                err(f"bad lang/kind {e.get('lang')}/{e.get('kind')}")
                continue
            lang, term = e["lang"], e["term"]
            forms = [term, *e.get("variants", [])]

            if lang == "zh" and not any(HAN.search(f) or f.isascii() for f in forms):
                err("zh term has no Han characters or ASCII form")
            if lang == "zh":
                short = len(term) <= 2 or term.isdigit()
                if (
                    short
                    and not e.get("ambiguous")
                    and e["kind"] in {"slang", "meme", "wordplay"}
                    and len(term) < 2
                ):
                    err("single-character term must be flagged ambiguous")
                if term.isdigit() and not e.get("ambiguous"):
                    err("digit slang must be flagged ambiguous (prices and quantities are common)")
            for f in forms:
                key = (f.lower(), "zh" if lang == "zh" else lang)
                if key in seen and seen[key] != term:
                    err(f"form {f!r} already defined by {seen[key]!r}")
                seen.setdefault(key, term)

            eq = e.get("equivalent", {})
            if bad := set(eq) - LANGS:
                err(f"equivalent has unknown languages {sorted(bad)}")
            if lang in eq:
                err("equivalent must not target the entry's own language")
            wanted = LANGS - {lang}
            if missing := wanted - set(eq):
                err(f"missing equivalent for {sorted(missing)}")
            if e.get("regex"):
                try:
                    re.compile(e["regex"])
                except re.error as ex:
                    err(f"bad regex: {ex}")
            if e["kind"] == "xiehouyu" and "——" not in term:
                err("歇后语 term must contain ——")
            if len(e["meaning"]) > 220:
                err("meaning too long (>220 chars)")
    counts = defaultdict(int)
    for lang, _kind in (
        (e["lang"], e["kind"])
        for p in lex_dir.glob("*.json")
        for e in json.loads(p.read_text(encoding="utf-8"))
    ):
        counts[lang] += 1
    print("entries:", dict(counts), file=sys.stderr)
    return errors


if __name__ == "__main__":
    problems = lint()
    for p in problems:
        print("ERROR", p)
    print(f"{len(problems)} problem(s)")
    sys.exit(1 if problems else 0)
