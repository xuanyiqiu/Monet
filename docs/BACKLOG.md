# Backlog

Legend: `[ ]` todo · `[~] (branch)` in progress · `[x]` done. Work top-down within your area. Acceptance criteria (AC) define "done".

## API (`services/api`) — owner: api agent
- [x] A-01 Core annotate pipeline: tokenize, pinyin, lexicon detection, LLM (fake/gemini/openai_compat), cache, `/v1/annotate`, `/v1/annotate/lines`, health, auth(dev), problem+json. AC: pytest green, contract conformance test.
- [x] A-02 `scripts/fetch_data.py`: download chinese-xinhua idioms → `data/generated/chengyu.json`. AC: ≥30k entries, loader test.
- [x] A-03 Dev playground `/playground` (dev only): textarea, langs, renders annotation. AC: manual check via screenshot.
- [x] A-04 Documents: upload (magic-byte validation), PDF (PyMuPDF blocks+bbox) / DOCX / TXT extraction, in-process job runner, pages endpoint. AC: tests with generated PDF incl. Chinese.
- [x] A-05 Postgres repo (SQLAlchemy async) + docker-compose `db` on 54329 + Alembic. AC: `-m db` tests pass against monet-db.
- [x] A-06 Postgres job queue (SKIP LOCKED, retries/backoff). AC: concurrency test.
- [x] A-07 Golden set (`tests/golden/*.yaml`) + live eval script with score report. AC: report in docs.
- [x] A-08 Supabase JWT auth + per-user rate limit. AC: tests with signed tokens.
- [x] A-09 Dockerfile + Cloud Run deploy notes. AC: image builds.

### API round 2 (after the Android Lens works end to end)
- [x] A-10 Supabase Storage `BlobStore` (blobs must survive Cloud Run restarts). AC: same interface as `LocalBlobStore`, contract test with a fake HTTP server.
- [ ] A-11 Scanned PDFs and images: server-side OCR (RapidOCR) feeding the same page/line model, with bboxes. AC: golden scanned fixture; clear error when OCR is unavailable.
- [~] (main) A-12 Lexicon growth: +300 memes/slang/idioms (zh priority: 2023-2026 slang, 谐音梗, 歇后语), each with origin and equivalents; a `scripts/lint_lexicon.py` that checks schema, duplicates and ambiguous-term flags. AC: lint in CI, golden cases added for new terms.
- [x] A-13 Feedback persistence (`feedback` table, migration 0003) + `scripts/feedback_report.py` listing the most reported terms. AC: tests on Postgres.
- [ ] A-14 Redis-backed rate limit and cache wired through `MONET_REDIS_URL` (docker compose profile `cache`). AC: tests with the real container.
- [ ] A-15 Semantic cache with pgvector for near-duplicate screen lines (OCR noise). AC: measurable hit-rate test on perturbed inputs.
- [x] A-16 Observability: structured JSON logs, request ids, per-endpoint latency and LLM-failure counters at `/v1/metrics` (dev) . AC: tests.

## Android (`apps/android`) — owner: android agent
- [x] F-01 Project scaffold: Gradle KTS + version catalog, minSdk 26, target/compile 35, Compose, theme from DESIGN_SYSTEM (tokens, fonts via downloadable fonts), light/dark. AC: `assembleDebug` green, screenshot of theme preview on emulator.
- [x] F-02 Network layer: kotlinx.serialization DTOs mirroring `contracts/openapi.yaml`, Retrofit `MonetApi`, `FakeMonetApi` with realistic sample data (memes, 成语), `AppContainer` DI, settings (DataStore). AC: unit tests for DTO parsing of contract examples.
- [x] F-03 Detail sheet: Meaning / Breakdown (ruby pinyin, tone colors, IPA) / Why-it-says-that cards; TTS play + slow; save/copy. AC: works with FakeMonetApi; screenshots light+dark.
- [x] F-04 Home: paste/type text + receive `ACTION_SEND`/`PROCESS_TEXT` from other apps → Detail sheet. AC: `adb shell am start` share intent test.
- [x] F-05 Settings screen (target lang, pinyin style, lines per step, theme). AC: persisted, unit tests.
- [x] F-06 Lens permissions + bubble overlay (foreground service, SYSTEM_ALERT_WINDOW), mode pill. AC: bubble shows over launcher on emulator.
- [x] F-07 Screen capture (MediaProjection) + ML Kit OCR (zh+latin) + line grouping. AC: unit tests on grouping, OCR test on a fixture bitmap.
- [x] F-08 Select mode: freeze frame, drag selection, translate selection card. AC: works end-to-end against local API.
- [x] F-09 Auto mode + reading band (N lines per step, next/prev, dim read lines). AC: logic unit-tested; screenshot.
- [x] F-10 Library + Reader (PdfRenderer + overlay from pages endpoint, reflow reader for docx/txt).
- [x] F-11 Onboarding.


### Android round 2 (hardening, missing spec features, release)
- [ ] F-12 Lens robustness: latest-frame cache (no "could not capture" on a static screen), stop cleanly when Android ends the projection (lock screen, system chip), rotation, pill kept on screen, no bubble flicker on capture. AC: unit tests for the pure parts; emulator check incl. rotation and projection stop.
- [ ] F-13 Auto mode follows the screen: `FrameGate` (frame diff + ~300 ms settle) re-reads after a scroll and re-anchors on the first unread unit; chips are tappable (open the Detail sheet); a ⌄ handle advances the band. AC: `FrameGate` and re-anchor logic unit-tested; emulator scroll test.
- [ ] F-14 Detail sheet completeness: Save to Notebook, Report a bad translation (`/v1/feedback`), tap a token to hear it alone, second tap on ▶ plays slow; on-device annotation cache so repeated text is instant and works offline. AC: unit tests; emulator check offline.
- [ ] F-15 Notebook: saved lines and expressions with their full Detail data (offline), search, delete, and a daily spaced-repetition review (5–10 cards). AC: scheduler unit-tested; persistence round-trip test.
- [ ] F-16 Settings from PRODUCT §2.5: source language, lines per step (1, 2, 3, 5, 10, All), overlay opacity, chip text size, TTS speed. AC: persisted + applied; unit tests.
- [ ] F-17 Typography: downloadable fonts (IBM Plex Sans, Source Serif 4, Noto Serif SC) with system fallback. AC: screenshot showing the real faces.
- [ ] F-18 Reader round 2: remembers the reading position, long-press opens the Detail sheet, upload from the share sheet, retry for failed pages/documents. AC: emulator check.
- [ ] F-19 Release build: R8 + resource shrinking, per-ABI APKs, release signing from a local keystore (never committed), version from git. AC: release APK installs and runs the Lens on the emulator; size reported.
- [ ] F-20 Accounts: Supabase Auth sign-in and token refresh (blocked: needs the Supabase project, see PROGRESS "Questions for the user").

## Later
- Notebook + spaced repetition, accounts sync, offline pinyin, EPUB/images, scanned-PDF OCR.
