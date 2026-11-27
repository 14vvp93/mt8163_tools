# MT8163 Tools

Modular Xposed/LSPosed tools for an MT8163 Android head unit.

## Platform

- Android 9 (API 28)
- Target display: 1280×720 at 160 dpi (mdpi)
- Legacy XposedBridge API 82
- LSPosed runtime
- Java 17, compile SDK 35

## Modules

| Module | Purpose | Current state |
|---|---|---|
| Media | Capture and restore media playback across ACC transitions | Implemented; device validation pending |
| Protection | Protect selected Recents tasks and exclude them from the MTK Memory Cleaner | Implemented; device validation pending |
| Voltage | Apply a software offset to the MCU-reported battery voltage | Implemented; device validation pending |
| Autorun | Module UI placeholder | No runtime implementation |
| Events | Store and display operational events | Implemented |

## Repository

- `app/` — Android application, module implementations, and Xposed integration
- `docs/` — English project documentation
- `.github/workflows/ci.yml` — lint and unit tests; no APK build
- `.github/workflows/manual-build.yml` — manually dispatched debug APK build
- `preview/index.html` — browser-based UI preview

## Build

The project uses Gradle 8.9 and Android Gradle Plugin 8.7.3. The repository includes the Gradle 8.9 Wrapper.

Run checks from the repository root:

~~~bash
./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest
~~~

Build the debug APK manually:

~~~bash
./gradlew --no-daemon :app:assembleDebug
~~~

GitHub Actions runs CI checks on pushes. APK builds remain manual.

## Documentation

Start at [docs/README.md](docs/README.md) for the documentation index and current repository structure.
