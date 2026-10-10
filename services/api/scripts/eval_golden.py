"""Live evaluation of the golden set against the configured LLM (reads .env).

    uv run python scripts/eval_golden.py                 # all cases, writes docs/EVAL.md
    uv run python scripts/eval_golden.py --only zh-meme  # ids containing a substring
    uv run python scripts/eval_golden.py --delay 4       # seconds between calls (free-tier friendly)

Metrics per case: translation present, expected expressions found (recall), forbidden expressions
absent (false-positive rejection), keyword check on the translation (loose, catches calques).
"""

import argparse
import asyncio
import sys
import time
from datetime import date
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from monet_api.annotator import Annotator  # noqa: E402
from monet_api.cache import MemoryCache  # noqa: E402
from monet_api.config import REPO_ROOT, Settings  # noqa: E402
from monet_api.llm import build_provider  # noqa: E402
from monet_api.main import shared_nlp  # noqa: E402
from monet_api.schemas import AnnotateRequest, Lang  # noqa: E402

CASES_PATH = ROOT / "tests" / "golden" / "cases.yaml"


def present(needle: str, found: list[str]) -> bool:
    n = needle.lower()
    return any(n in f or f in n for f in found)


async def run_case(annotator: Annotator, case: dict, retries: int = 2) -> dict:
    req = AnnotateRequest(text=case["text"], target_lang=Lang(case["target"]))
    ann = None
    for attempt in range(retries + 1):
        ann = await annotator.annotate(req)
        if ann.translation:
            break
        if attempt < retries:
            await asyncio.sleep(15 * (attempt + 1))  # free-tier 429s clear quickly
    assert ann is not None
    found = [e.text.lower() for e in ann.expressions]
    tr = ann.translation.lower()
    expected = [*case.get("expect_dict", []), *case.get("expect_model", [])]
    missing = [e for e in expected if not present(e, found)]
    false_pos = [f for f in case.get("forbid", []) if present(f, found)]
    tr_any = case.get("tr_any")
    kw_ok = True if not tr_any else any(k.lower() in tr for k in tr_any)
    calque = [k for k in case.get("tr_none", []) if k.lower() in tr]
    ok = bool(ann.translation) and not missing and not false_pos and kw_ok and not calque
    return {
        "id": case["id"], "ok": ok, "translation": ann.translation, "missing": missing,
        "false_pos": false_pos, "kw_ok": kw_ok, "calque": calque, "text": case["text"],
        "target": case["target"], "n_expected": len(expected), "n_forbid": len(case.get("forbid", [])),
    }  # fmt: skip


def report(results: list[dict], model: str, seconds: float) -> str:
    n = len(results)
    passed = sum(r["ok"] for r in results)
    exp = sum(r["n_expected"] for r in results)
    miss = sum(len(r["missing"]) for r in results)
    forb = sum(r["n_forbid"] for r in results)
    fp = sum(len(r["false_pos"]) for r in results)
    got_tr = sum(bool(r["translation"]) for r in results)
    kw = sum(r["kw_ok"] and not r["calque"] for r in results)
    lines = [
        "# Golden-set evaluation",
        "",
        f"- Date: {date.today().isoformat()} · Model: `{model}` · {n} cases · {seconds:.0f} s",
        f"- **Cases passed: {passed}/{n} ({100 * passed / n:.0f}%)**",
        f"- Translation returned: {got_tr}/{n}",
        f"- Expression recall: {exp - miss}/{exp} ({100 * (exp - miss) / max(exp, 1):.0f}%)",
        f"- False positives rejected: {forb - fp}/{forb}",
        f"- Translation keyword check (no calques): {kw}/{n}",
        "",
        "Regenerate with `uv run python scripts/eval_golden.py` (needs an LLM key in `.env`).",
        "",
        "| Case | OK | Translation | Notes |",
        "|---|---|---|---|",
    ]
    for r in results:
        notes = []
        if not r["translation"]:
            notes.append("no translation (LLM unavailable)")
        if r["missing"]:
            notes.append("missing: " + ", ".join(r["missing"]))
        if r["false_pos"]:
            notes.append("false positive: " + ", ".join(r["false_pos"]))
        if not r["kw_ok"]:
            notes.append("keyword check failed")
        if r["calque"]:
            notes.append("calque: " + ", ".join(r["calque"]))
        tr = (r["translation"] or "—").replace("|", "\\|").replace("\n", " ")
        lines.append(f"| {r['id']} | {'✓' if r['ok'] else '✗'} | {tr} | {'; '.join(notes)} |")
    return "\n".join(lines) + "\n"


async def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")  # Windows consoles default to cp1252
    sys.stderr.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", help="substring of case ids to run")
    ap.add_argument("--delay", type=float, default=3.0, help="seconds between calls")
    ap.add_argument("--no-write", action="store_true")
    args = ap.parse_args()

    settings = Settings()
    if settings.llm_provider == "fake":
        print("MONET_LLM_PROVIDER is 'fake': set a real provider in .env to evaluate.", file=sys.stderr)
        return 2
    lexicon, tokenizer = shared_nlp(settings)
    llm = build_provider(settings)
    annotator = Annotator(lexicon, tokenizer, llm, MemoryCache())

    cases = yaml.safe_load(CASES_PATH.read_text(encoding="utf-8"))
    if args.only:
        cases = [c for c in cases if args.only in c["id"]]
    results, t0 = [], time.time()
    for i, case in enumerate(cases, 1):
        r = await run_case(annotator, case)
        results.append(r)
        print(
            f"[{i:2}/{len(cases)}] {'ok ' if r['ok'] else 'FAIL'} {r['id']}  {r['translation'][:60]!r}",
            flush=True,
        )
        await asyncio.sleep(args.delay)

    text = report(results, llm.model, time.time() - t0)
    print("\n" + "\n".join(text.splitlines()[:9]))
    if not args.no_write and not args.only:
        (REPO_ROOT / "docs" / "EVAL.md").write_text(text, encoding="utf-8")
        print("\nwrote docs/EVAL.md")
    return 0 if all(r["ok"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
