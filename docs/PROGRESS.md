# Progress log

Append newest at the bottom. Keep entries short: what was done, what's next, gotchas.

## Questions for the user
- (none yet)

## Contract requests
- (none yet)

## Log
- 2026-10-10 · setup · Portable toolchain in `.tools` (bootstrap.ps1), DB guard hook, docs (PRODUCT, ARCHITECTURE, DESIGN_SYSTEM), contract v0.1.0, backlog. API A-01 in progress on main.
- 2026-10-10 · api · A-01 done: annotate pipeline (jieba + pypinyin w/ word context, lexicon matcher for 成语/梗/歇后语/es+en idioms, grounded LLM prompts, fake/gemini/openai_compat providers, tiered cache, degraded mode), `/v1/health|me|annotate|annotate/lines|feedback`, problem+json, dev auth, rate limit, contract-conformance test. 48 tests. Note: a translation of "" means the LLM was unavailable (see `note`). Next: A-02 chengyu data, A-03 playground.
- 2026-10-10 · android · F-01 scaffold: Gradle KTS + version catalog (AGP 8.7.3, Kotlin 2.1, Compose BOM 2024.12), minSdk 26 / target 35, `MonetTheme` with DESIGN_SYSTEM color + type tokens (light/dark), theme preview screen, seal launcher icon. Gradle 8.12 lives in `.tools/gradle` (`GRADLE_USER_HOME=.tools/gradle-home`). Gotcha: downloadable Google Fonts need the Play Services cert array, not added yet, so system fonts are used (`MonetFonts.System`). Next: F-02 network layer.
