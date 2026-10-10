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

## Android (`apps/android`) — owner: android agent
- [x] F-01 Project scaffold: Gradle KTS + version catalog, minSdk 26, target/compile 35, Compose, theme from DESIGN_SYSTEM (tokens, fonts via downloadable fonts), light/dark. AC: `assembleDebug` green, screenshot of theme preview on emulator.
- [x] F-02 Network layer: kotlinx.serialization DTOs mirroring `contracts/openapi.yaml`, Retrofit `MonetApi`, `FakeMonetApi` with realistic sample data (memes, 成语), `AppContainer` DI, settings (DataStore). AC: unit tests for DTO parsing of contract examples.
- [x] F-03 Detail sheet: Meaning / Breakdown (ruby pinyin, tone colors, IPA) / Why-it-says-that cards; TTS play + slow; save/copy. AC: works with FakeMonetApi; screenshots light+dark.
- [x] F-04 Home: paste/type text + receive `ACTION_SEND`/`PROCESS_TEXT` from other apps → Detail sheet. AC: `adb shell am start` share intent test.
- [x] F-05 Settings screen (target lang, pinyin style, lines per step, theme). AC: persisted, unit tests.
- [ ] F-06 Lens permissions + bubble overlay (foreground service, SYSTEM_ALERT_WINDOW), mode pill. AC: bubble shows over launcher on emulator.
- [ ] F-07 Screen capture (MediaProjection) + ML Kit OCR (zh+latin) + line grouping. AC: unit tests on grouping, OCR test on a fixture bitmap.
- [ ] F-08 Select mode: freeze frame, drag selection, translate selection card. AC: works end-to-end against local API.
- [ ] F-09 Auto mode + reading band (N lines per step, next/prev, dim read lines). AC: logic unit-tested; screenshot.
- [ ] F-10 Library + Reader (PdfRenderer + overlay from pages endpoint, reflow reader for docx/txt).
- [ ] F-11 Onboarding.

## Later
- Notebook + spaced repetition, accounts sync, offline pinyin, EPUB/images, scanned-PDF OCR.
