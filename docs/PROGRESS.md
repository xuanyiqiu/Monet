# Progress log

Append newest at the bottom. Keep entries short: what was done, what's next, gotchas.

## Questions for the user
- (none yet)

## Contract requests
- (none yet)

## Log
- 2026-10-10 · setup · Portable toolchain in `.tools` (bootstrap.ps1), DB guard hook, docs (PRODUCT, ARCHITECTURE, DESIGN_SYSTEM), contract v0.1.0, backlog. API A-01 in progress on main.
- 2026-10-10 · api · A-01 done: annotate pipeline (jieba + pypinyin w/ word context, lexicon matcher for 成语/梗/歇后语/es+en idioms, grounded LLM prompts, fake/gemini/openai_compat providers, tiered cache, degraded mode), `/v1/health|me|annotate|annotate/lines|feedback`, problem+json, dev auth, rate limit, contract-conformance test. 48 tests. Note: a translation of "" means the LLM was unavailable (see `note`). Next: A-02 chengyu data, A-03 playground.
- 2026-10-10 · api · A-02 done: `scripts/fetch_data.py` builds 30,888 成语 from chinese-xinhua into `data/generated/` (gitignored; run once after clone). Load 1.1 s, detection <1 ms. +9 workplace/dating memes (画大饼, 996, 背锅, 舔狗…).
- 2026-10-10 · api · A-03 done: dev playground at http://localhost:8000/playground (Detail-sheet reference UI: ruby pinyin with tone colors, underlines, expression cards). Screenshot-verified via headless Chrome.
- 2026-10-10 · api · A-04 done: documents API (upload with magic-byte sniffing, 25 MB/500 page limits, PDF lines with normalized bboxes via PyMuPDF, DOCX/TXT reflow into paragraph blocks + sentence lines, ~1800 chars/page). Doc is `ready` after first 3 pages are annotated; the rest continue in background and unannotated pages are annotated on demand (free-tier friendly; contract `progress` description updated). In-memory repo + local blobs for now (Postgres = A-05). 62 tests.
