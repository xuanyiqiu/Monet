"""Lint a worker task spec (docs/tasks/*.md) before it is handed to a worker model.

Mechanical checks only: it catches missing or vague specs, not wrong design decisions.
Exit code 1 if any ERROR is found; WARN lines are advice.

Usage: uv run --no-project python scripts/lint_task.py docs/tasks/A-10.md
"""

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

REQUIRED_HEADINGS = ["context", "files to edit", "do not touch", "behaviour", "tests", "acceptance", "worker report"]
REQUIRED_FIELDS = ["Branch", "Goal"]

# Words that make a cheap model guess. Each needs to be replaced by a concrete rule.
VAGUE = [
    r"\bimprove\w*", r"\bproperly\b", r"\bappropriate\w*", r"\betc\b\.?", r"\band so on\b", r"\bas needed\b",
    r"\bedge cases?\b", r"\bclean ?up\b", r"\brobust\w*", r"\bnice\b", r"\bgood\b", r"\boptimi[sz]\w*",
    r"\bwherever\b", r"\bsomehow\b", r"\bif necessary\b", r"\bfeel free\b", r"\bshould work\b",
]
PLACEHOLDER = re.compile(r"<[^>\n]{2,}>|\bTODO\b|\bTBD\b|\bT-XX\b|^\s*-\s*\.\.\.\s*$", re.M)
CMD_WORDS = ("pytest", "ruff", "gradlew", "uv.exe", "npm", "ktlint", "lint")
FORBIDDEN_EDIT = ("contracts/openapi.yaml", ".env")


def sections(text: str) -> dict[str, str]:
    out: dict[str, str] = {}
    name = None
    for line in text.splitlines():
        m = re.match(r"^##\s+(.*)$", line)
        if m:
            name = m.group(1).strip().lower()
            out[name] = ""
        elif name is not None:
            out[name] += line + "\n"
    return out


def find(secs: dict[str, str], key: str) -> str | None:
    for name, body in secs.items():
        if name.startswith(key):
            return body
    return None


def lint(path: Path) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    warns: list[str] = []
    text = path.read_text(encoding="utf-8")
    secs = sections(text)

    if not re.match(r"^# [A-Z]+-\w+: \S", text):
        errors.append("first line must be `# <ID>: <title>` (e.g. `# A-10: Supabase Storage BlobStore`)")
    for f in REQUIRED_FIELDS:
        if not re.search(rf"^\*\*{f}:\*\*\s*\S", text, re.M):
            errors.append(f"missing `**{f}:**` line")
    for h in REQUIRED_HEADINGS:
        if find(secs, h) is None:
            errors.append(f"missing section `## {h.title()}`")

    # Everything above "Worker report" is the instruction part; the report is written by the worker.
    body = text.split("## Worker report")[0]

    prose = re.sub(r"`[^`\n]*`", "``", body)  # `code spans` may legitimately contain <angle> text
    for m in PLACEHOLDER.finditer(prose):
        errors.append(f"unfilled placeholder: {m.group(0).strip()!r}")
    if "path/to/" in body:
        errors.append("template path `path/to/...` left in the spec")
    for pat in VAGUE:  # code spans are exempt: file and command names may contain these words
        for m in re.finditer(pat, prose, re.I):
            line = body.count("\n", 0, m.start()) + 1
            errors.append(f"vague wording {m.group(0)!r} on line {line}: replace with a concrete rule")

    files = find(secs, "files to edit") or ""
    paths = []
    for line in files.splitlines():
        m = re.match(r"^\s*-\s*`([^`]+)`", line)
        if not m:
            continue
        p = m.group(1).strip()
        paths.append(p)
        is_new = "NEW" in line
        full = REPO / p
        if any(p.endswith(x) or p == x for x in FORBIDDEN_EDIT):
            errors.append(f"must not be edited by a worker: {p}")
        elif is_new and full.exists():
            errors.append(f"marked NEW but already exists: {p}")
        elif is_new and not full.parent.exists():
            warns.append(f"NEW file's folder does not exist yet (the worker creates it): {p}")
        elif not is_new and not full.exists():
            errors.append(f"file to edit does not exist (mark it NEW if it should be created): {p}")
    if not paths:
        errors.append("`## Files to edit` must list files as `- \\`path\\``")
    if len(paths) > 5:
        warns.append(f"{len(paths)} files: split the task (cheap models do best with 1-4 files)")

    acc = find(secs, "acceptance") or ""
    cmds = re.findall(r"`([^`]+)`", acc)
    if not any(any(w in c for w in CMD_WORDS) for c in cmds):
        errors.append("`## Acceptance` needs at least one runnable test/lint command in backticks")

    tests = find(secs, "tests") or ""
    n_tests = len(re.findall(r"^\s*\d+\.\s", tests, re.M))
    if n_tests < 3:
        warns.append(f"only {n_tests} numbered test cases (aim for 3-8, each a concrete input -> output)")
    if n_tests > 10:
        warns.append(f"{n_tests} test cases: probably too big for one task")

    beh = find(secs, "behaviour") or ""
    if len(beh.strip().splitlines()) < 3:
        errors.append("`## Behaviour` is too thin: write the decisions (names, signatures, error cases)")
    if len(body.splitlines()) > 90:
        warns.append(f"spec is {len(body.splitlines())} lines: long specs hide the important decisions")
    return errors, warns


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: lint_task.py docs/tasks/<ID>.md")
        return 2
    path = (REPO / argv[1]) if not Path(argv[1]).is_absolute() else Path(argv[1])
    errors, warns = lint(path)
    for w in warns:
        print(f"WARN  {w}")
    for e in errors:
        print(f"ERROR {e}")
    print(f"{'FAIL' if errors else 'OK'}: {path.name} ({len(errors)} errors, {len(warns)} warnings)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
