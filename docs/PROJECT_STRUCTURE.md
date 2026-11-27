# Project Structure

This document describes the current source layout and dependency boundaries on `development`.

## Repository layout

~~~text
MT8163 Tools/
├── .github/workflows/
├── app/
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/xposed_init
│       │   ├── java/com/mt8163/tools/
│       │   │   ├── core/
│       │   │   ├── modules/
│       │   │   │   ├── autorun/
│       │   │   │   ├── events/
│       │   │   │   ├── media/
│       │   │   │   ├── protection/
│       │   │   │   └── voltage/
│       │   │   ├── ui/
│       │   │   └── xposed/
│       │   │       ├── hooks/
│       │   │       │   ├── media/
│       │   │       │   ├── protection/
│       │   │       │   └── voltage/
│       │   │       ├── HookRegistry.java
│       │   │       ├── PackageHook.java
│       │   │       ├── SystemBootstrap.java
│       │   │       ├── SystemConfigReceiver.java
│       │   │       └── XposedEntry.java
│       │   └── res/
│       └── test/
├── docs/
│   ├── architecture/
│   ├── modules/
│   └── xposed/
├── preview/
├── gradle/
├── gradlew
└── gradlew.bat
~~~

All project documentation belongs in the repository-root `docs/` directory and is written in English.

## Application architecture

### Core

`com.mt8163.tools.core` contains shared contracts and infrastructure:

- `ModuleContract` — module identity, title/layout resources, and enabled state.
- `ModuleRegistry` — stores and exposes module definitions.
- `PreferencesManager` — persists module enabled states.
- `EventLogger` — shared event-submission API.

Core does not construct feature modules or depend on their UI implementations.

### Modules

Each feature module owns its settings, runtime logic, and UI implementation under `modules/<module>/`.

`ModuleCatalog` is the composition root. It creates the modules in UI order and supplies them to the registry.

`ModuleUiContract` lets each module create its content view and handle module-specific lifecycle callbacks and activity results. The application shell uses these shared contracts rather than importing feature view classes.

| Module | Package | Current state |
|---|---|---|
| Media | `modules/media` | ACC Snapshot and playback restoration implemented |
| Protection | `modules/protection` | Recents and Memory Cleaner protection implemented |
| Voltage | `modules/voltage` | MCU voltage offset implemented |
| Events | `modules/events` | Event storage and history UI implemented |
| Autorun | `modules/autorun` | UI placeholder only |

Modules do not depend on one another. Event producers use Core's `EventLogger`; they do not access Events storage or UI.

### UI

`com.mt8163.tools.ui` contains the shared activity shell, navigation, and common UI infrastructure. Individual module layouts and view behavior remain in their owning module packages.

## Xposed architecture

Xposed integration is separate from the application modules:

- `XposedEntry` — Legacy Xposed package-load entry point, registered through `assets/xposed_init`.
- `HookRegistry` — routes package-load callbacks and module enable/disable requests.
- `PackageHook` — common hook contract: target-package support, installation, module ID, and uninstallation.
- `SystemBootstrap` — installs SystemServer startup hooks and initializes shared configuration delivery.
- `SystemConfigReceiver` — routes Media and Voltage configuration broadcasts in `system_server`.
- `xposed/hooks/<module>/` — module-specific Xposed implementations.

Current hook routing:

| Target process | Hook | Module |
|---|---|---|
| `android` | `MediaHook` | Media |
| `android` | `VoltageHook` | Voltage |
| `com.android.systemui` main process | `ProtectionHook` | Protection |

Events and Autorun do not have Xposed hooks. Events is an application-side event consumer; Autorun is currently a UI placeholder.

## Configuration and process boundaries

Media and Voltage hooks run in `system_server`. Protection runs in the main `com.android.systemui` process. Static fields are process-local and are not shared between the application, SystemServer, and SystemUI.

The application persists settings and publishes module-specific configuration through explicit broadcasts protected by the corresponding permissions. Each target process loads its initial configuration and handles subsequent updates locally.

## Platform and build

- Android 9 / API 28.
- Legacy XposedBridge API 82, running under LSPosed.
- Compile SDK 35; Java 17.
- Android Gradle Plugin 8.7.3; Gradle Wrapper 8.9.
- CI runs lint and unit tests.
- Debug APK builds are manual-only.

## Device constraints

- 1280×720 at 160 dpi (mdpi).
- Keep layouts usable within the target display.
- Use callbacks, listeners, broadcasts, and lifecycle events.
- Do not introduce polling or periodic timers.
- Framework and vendor behavior must be validated on the actual head unit.
