# Monet

Read anything on your screen and actually *get* it: idioms, memes, wordplay and tone included, not only the words.

Monet is an Android app plus an API that reads text on screen (over any app) or in an uploaded PDF/DOCX and explains it: a natural translation, pinyin or IPA with audio, and a "why it says that" card for every 成语, meme, slang term, pun or idiom. Launch languages: **Chinese, Spanish and English**, in any direction, with the deepest coverage for Chinese internet culture.

> 蚌埠住了 → *"pun on 绷不住了: I can't hold it together (laughing or crying)"*, not "Bengbu stopped".

## How it understands

Language models alone hallucinate meme origins and mangle polyphonic characters, so Monet is hybrid:

1. **Deterministic NLP.** jieba segmentation and pypinyin with word context (银行 → *yín háng*, 行走 → *xíng zǒu*). The LLM never produces pinyin.
2. **Curated lexicons.** About 31 000 成语 (chinese-xinhua) plus hand-curated memes, 歇后语 and slang for zh/es/en, matched in under a millisecond.
3. **Grounded LLM.** Dictionary hits are passed to the model as reference facts. It confirms them in context (a price of "666元" is not slang), adds what the dictionaries missed, and writes the explanation in the reader's language.
4. **Honesty.** Every expression carries a `source` (dictionary / model / both) and a confidence. If the model is unavailable the API still returns pinyin and dictionary results instead of failing.

Measured on a 43-case golden set with the real model: see [docs/EVAL.md](docs/EVAL.md).

## Architecture

```
Android (Kotlin, Compose)                    API (Python 3.12, FastAPI)
 ├─ Lens: MediaProjection + overlay          ├─ /v1/annotate, /v1/annotate/lines
 ├─ On-device OCR (ML Kit, zh + latin)       ├─ /v1/documents (PDF, DOCX, TXT)
 ├─ Detail sheet, Reader, Library            ├─ Postgres (+ pgvector), durable job queue
 └─ Room cache, DataStore, TTS               └─ Gemini, with model fallback chain and cache
            └──────── contracts/openapi.yaml (source of truth) ────────┘
```

Screenshots never leave the phone: OCR runs on the device and only the recognized text is sent. Details and decisions in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); the product spec is [docs/PRODUCT.md](docs/PRODUCT.md) and the UI rules are [docs/DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md).

Engineering highlights: contract-first API with a conformance test, Postgres job queue on `FOR UPDATE SKIP LOCKED` (no broker), Supabase JWT verification with HS256 and JWKS, free-tier friendly retries and model fallback, 139 backend tests, CI for both apps.

## Run it

Requirements: Windows with Docker Desktop. Everything else is installed into `.tools/` by one script.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\bootstrap.ps1 -WithEmulator   # JDK 21, Android SDK, uv, Python 3.12
docker compose up -d db                                                         # Postgres on :54329
copy .env.example .env                                                          # add a free Gemini key (aistudio.google.com/apikey)
cd services\api
..\..\.tools\bin\uv.exe run python scripts\fetch_data.py                        # one-time: full 成语 lexicon
..\..\.tools\bin\uv.exe run monet-api                                           # http://localhost:8000/playground
```

Backend tests: `uv run pytest -q`. Android: `cd apps\android && .\gradlew.bat testDebugUnitTest lintDebug assembleDebug`.

Deploying to the free tier: [docs/DEPLOY.md](docs/DEPLOY.md).

## Status

See [docs/BACKLOG.md](docs/BACKLOG.md) and [docs/PROGRESS.md](docs/PROGRESS.md). The API milestones are complete; the Android Lens (screen capture, OCR, select and auto modes, document reader) is in progress.
