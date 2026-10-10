# Monet

Android app (Kotlin/Compose) + Python API that reads on-screen text and explains it: translation, pinyin/IPA, 成语, memes, slang. zh/es/en.

Read first: `docs/PRODUCT.md` (what), `docs/ARCHITECTURE.md` (how), `docs/DESIGN_SYSTEM.md` (UI rules), `docs/BACKLOG.md` (tasks), `docs/PROGRESS.md` (state + handoff notes).

## Hard rules
- **NEVER touch the user's local PostgreSQL** (Windows service, default port, database named `postgres`). It is their WORK database. Monet's DB is Docker `monet-db` on port **54329**, db `monet`. A hook enforces this; never try to bypass it.
- Everything stays inside `D:\Monet`. Toolchain is portable in `.tools/` (JDK 21, Android SDK, uv, Python 3.12, gh, adb). Never install global tools.
- `contracts/openapi.yaml` is the API source of truth. Do not change it in a feature branch; if you need a change, write it in `docs/PROGRESS.md` under "Contract requests" and stop that part.
- Stay in your area: Android agent edits `apps/android/**`; API agent edits `services/api/**`. Shared docs: only append to `docs/PROGRESS.md`.
- Never commit secrets (`.env`, keys). Never force-push. Never rewrite `main` history.
- **No AI attribution anywhere**: no `Co-Authored-By: Claude…`, no "Generated with Claude Code" in commits, PRs, code or docs. Plain Conventional Commits only.

## Workflow (every task)
1. Pick the first unchecked task for your area in `docs/BACKLOG.md`; mark it `[~]` with your branch name.
2. Implement with tests. Done = tests + lint green (commands below).
3. Commit on your branch (Conventional Commits), check the task `[x]`, append a 3–5 line entry to `docs/PROGRESS.md`.
4. Continue with the next task without asking, unless blocked by a missing account/key or a product decision. Then write the question in PROGRESS.md "Questions for the user" and move to another task.

## Commands
API (`services/api`): `../../.tools/bin/uv.exe run pytest -q` · `../../.tools/bin/uv.exe run ruff check .` · run: `../../.tools/bin/uv.exe run monet-api`
Android (`apps/android`): `./gradlew.bat testDebugUnitTest lintDebug assembleDebug`
Emulator: `.tools/android-sdk/emulator/emulator.exe -avd monet -no-snapshot-save` · screenshot: `.tools/bin/adb.exe exec-out screencap -p > shot.png` (then read the PNG to check UI).
