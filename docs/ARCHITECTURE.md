# Monet: architecture

## 1. System overview

```
┌──────────────────────── Android app (Kotlin, Jetpack Compose) ───────────────────────┐
│                                                                                      │
│  LensService (foreground)                     App UI                                 │
│   ├─ MediaProjection → ImageReader frames      ├─ Detail sheet, Library, Reader      │
│   ├─ FrameGate (change detection, throttling)  ├─ Notebook, Settings, Onboarding     │
│   ├─ ML Kit Text Recognition v2 (zh + latin)   └─ PdfRenderer (native page bitmaps)  │
│   ├─ LineGrouper → ReadingBand                                                       │
│   └─ Overlay windows (TYPE_APPLICATION_OVERLAY, Compose in a WindowManager view)     │
│                                                                                      │
│  Core: MonetApi (Retrofit + kotlinx.serialization), Room cache, DataStore settings,  │
│        TextToSpeech, AppContainer (manual DI)                                        │
└───────────────────────────────┬──────────────────────────────────────────────────────┘
                                │ HTTPS, Bearer JWT, JSON — contracts/openapi.yaml
┌───────────────────────────────▼────────────── API (Python 3.12, FastAPI) ────────────┐
│ /v1/annotate, /v1/annotate/lines                                                     │
│   Normalizer → LangDetect → Segmenter (jieba) → Phonetics (pypinyin)                 │
│   → ExpressionDetector (lexicon trie: 成语, 梗, slang, idioms)                        │
│   → LLM (grounded with the detector's hits) → schema-validated JSON                  │
│   → Merge (dictionary facts beat model guesses) → Cache                              │
│ /v1/documents                                                                        │
│   Upload → BlobStore → Job queue (Postgres SKIP LOCKED) → Worker:                    │
│   extract (PyMuPDF / python-docx) → blocks with bboxes → batched /lines annotate     │
│   → persisted per page                                                               │
└──────┬──────────────────┬──────────────────────┬────────────────────────┬────────────┘
   Postgres 16/17       Redis (optional)       BlobStore               LLM provider
   (+pgvector later)    hot cache              local disk / Supabase   Gemini | OpenAI-compatible
                                                Storage                (Groq, DeepSeek, Qwen…)
```

## 2. Decisions (ADRs, short form)

**ADR-001: Android native first (Kotlin + Compose); no iOS in v1.**
The Lens needs `MediaProjection` plus `SYSTEM_ALERT_WINDOW`, which are native Android APIs; iOS forbids overlays over other apps. A cross-platform framework would still need native code for the core feature, so it buys nothing.

**ADR-002: OCR on the device (ML Kit Text Recognition v2, Chinese + Latin models).**
It is free, works offline, takes about 100–300 ms per frame and keeps screenshots private. The server never receives images from the Lens. Documents are processed on the server: PDF text layers via PyMuPDF; scanned PDFs → OCR via RapidOCR/PaddleOCR (M5+).

**ADR-003: Hybrid understanding = deterministic NLP + lexicons + LLM.**
LLMs alone hallucinate meme origins and mangle 多音字. So:
- Pinyin comes from pypinyin with phrase-level disambiguation, never from the LLM. The LLM may only flag a disputed reading.
- Expressions are detected by longest-match over curated lexicons: 成语 (chinese-xinhua, ~31k), CC-CEDICT, and Monet's own hand-curated `memes.*.json`.
- Their definitions are injected into the prompt as *grounding*. The model must confirm each candidate in context (e.g. "666" as praise vs. a price) and may add expressions the lexicon missed, marked `source: model`.
- Results carry `confidence` and `source`, and the UI is honest about which is which.

**ADR-004: Provider-agnostic LLM layer.**
`LLMProvider` protocol with three implementations: `gemini` (default; free tier, native JSON schema output), `openai_compat` (Groq, DeepSeek, Qwen/DashScope, OpenRouter, local Ollama) and `fake` (deterministic, for tests and CI). Switching provider is a config change.

**ADR-005: Two annotation depths.**
- `fast`: translation + pinyin + expression spans. It feeds the overlay, and up to 20 lines share one LLM call so context flows across lines.
- `full`: gloss, IPA and cultural cards. It feeds the Detail sheet.
Keeping the overlay cheap is what keeps the free tier viable.

**ADR-006: Cache everywhere.**
The key is `sha256(prompt_version | model | depth | src | tgt | normalized_text)`. The cache has three tiers: on-device Room → server in-process LRU → Redis/Postgres. A repeated meme costs zero tokens.

**ADR-007: Contract-first API.**
`contracts/openapi.yaml` is the single source of truth.
- Backend: tests assert that the live schema matches the contract.
- Android: DTOs mirror it, and `FakeMonetApi` lets the UI be built without a server.
- Only the architect role changes the contract; a change goes in its own commit and updates both sides.

**ADR-008: Postgres-backed job queue (no extra broker).**
Workers claim jobs with `SELECT … FOR UPDATE SKIP LOCKED`. This means retries with backoff and idempotent steps, and no Celery/RabbitMQ on a free tier. Dev/test mode uses an in-process asyncio worker.

**ADR-009: Auth via Supabase JWT; the API stays stateless.**
- The API verifies JWTs (JWKS or HS256 secret).
- `MONET_AUTH_MODE=dev` accepts `dev:<user-id>` tokens for local work and tests.
- Postgres row ownership is enforced in queries (`user_id = :uid`); RLS is added when deploying on Supabase.

**ADR-010: Manual DI on Android; no Hilt in v1.**
An `AppContainer` keeps builds fast and avoids annotation processing, which makes it easier for agents to work on without breaking builds. Hilt can be added later if the graph grows.

## 3. Security

- No secrets in the app: all LLM calls go through the API, and keys live in server env vars only.
- JWT on every `/v1/*` route except health; per-user token-bucket rate limit; request body size limits; upload type sniffing (magic bytes, not the file extension); max 25 MB and 500 pages.
- Uploaded files go to private storage under `user_id/` and are read back only by their owner.
- Prompt-injection hardening: user text is passed as data inside a delimited field, the model output must validate against a JSON schema, and nothing from the model is executed or rendered as HTML.
- The Lens never captures apps in the exclusion list. Windows flagged `FLAG_SECURE` already come out black.
- Errors are returned as `application/problem+json`, with no stack traces in production.

## 4. Repository layout

```
apps/android/          Android app (Gradle, Kotlin DSL, version catalog)
services/api/          FastAPI service (uv, src layout, pytest)
contracts/openapi.yaml API contract (source of truth)
docs/                  PRODUCT, ARCHITECTURE, DESIGN_SYSTEM, BACKLOG, PROGRESS
scripts/               bootstrap.ps1 (portable toolchain), dev helpers
.claude/               agents, commands, hooks, settings (shared agent config)
.tools/  (ignored)     JDK 21, Android SDK, uv, Python, gh, caches
.data/   (ignored)     local blobs, Postgres volume
```

## 5. Environments

| | Local dev | Production (free tier) |
|---|---|---|
| API | `uv run monet-api` on :8000 | Google Cloud Run (or an Oracle Cloud Always Free VM) |
| DB | Docker `monet-db` on **:54329**, db `monet` | Supabase Postgres |
| Cache | in-process LRU | Upstash Redis |
| Blobs | `.data/blobs` | Supabase Storage |
| LLM | `fake`, or Gemini with a dev key | Gemini Flash free tier, OpenAI-compatible fallback |

⚠️ The developer's machine also runs a separate PostgreSQL service whose `postgres` database is a **work database**. Monet never connects to it; `.claude/hooks/guard-db.mjs` enforces this.
