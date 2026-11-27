# MT8163 Tools

Modular Xposed/LSPosed tools for an MT8163 Android head unit.

## Platform

- Android 9 (API 28)
- Target display: 1280×720 at 160 dpi (mdpi)
- Legacy XposedBridge API 82
- Java 17, compile SDK 35
- LSPosed runtime

## Modules

| Module | Purpose |
|---|---|
| Media | Capture the active audio source and playback state across ACC transitions; restore playback and show configured Toast notifications |
| Protection | Protect selected applications from Recents dismissal and MTK Memory Cleaner cleanup |
| Voltage | Apply a configurable software offset to the MCU-reported battery voltage |
| Events | Store and display operational events from the modules |
| Autorun | UI placeholder; implementation is not part of the current work package |

## Repository

- app/ — Android application and Legacy Xposed integration
- docs/ — English project and implementation documentation
- .github/workflows/ci.yml — lint and unit tests; no APK build
- .github/workflows/manual-build.yml — manually dispatched debug APK build
- preview/index.html — browser-based UI preview

## Build

The project uses Gradle 8.9 and Android Gradle Plugin 8.7.3. The repository currently does not include the Gradle Wrapper.

Run checks from the repository root with Gradle 8.9:

~~~bash
gradle --no-daemon :app:lintDebug :app:testDebugUnitTest
~~~

Build the debug APK manually:

~~~bash
gradle --no-daemon :app:assembleDebug
~~~

GitHub Actions keeps APK builds manual. Pushes to development run CI checks only.

## Documentation

- [Project structure](docs/PROJECT_STRUCTURE.md)
- [Project audit and remaining validation](docs/PROJECT_AUDIT.md)
- [Dynamic module lifecycle](docs/architecture/dynamic-module-lifecycle.md)
- [Media module](docs/modules/media/README.md)
- [Protection module](docs/modules/protection/README.md)
- [Voltage module](docs/modules/voltage/README.md)
- [Events module](docs/modules/events/README.md)
