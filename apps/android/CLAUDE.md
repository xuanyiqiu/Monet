# Android app rules

- Kotlin 2.x, Jetpack Compose (Material3 as base, but visuals come from `docs/DESIGN_SYSTEM.md` tokens: no default purple M3 look).
- Package `dev.monet.app`. Modules start as one `:app` module; packages: `core/` (network, data, di, settings), `ui/theme`, `feature/detail`, `feature/home`, `feature/lens`, `feature/library`, `feature/settings`.
- Manual DI via `AppContainer` (no Hilt). Coroutines + StateFlow, one ViewModel per screen, immutable UI state.
- DTOs mirror `contracts/openapi.yaml` exactly (snake_case via `@SerialName`). Build UI against `FakeMonetApi` until the real API is reachable at `http://10.0.2.2:8000` (emulator → host), dev token `dev:android`.
- Every screen: light + dark previews, works at 200% font scale, content descriptions.
- Verify UI on the emulator with screenshots (see root CLAUDE.md) before marking a UI task done.
- JDK: `JAVA_HOME=D:\Monet\.tools\jdk-21`; SDK: `D:\Monet\.tools\android-sdk` (write it to `local.properties` as `sdk.dir`).
