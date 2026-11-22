# Project structure

This document describes the current repository layout and the responsibilities of its main layers. It must reflect files that actually exist in the repository.

## Repository layout

```text
MT8163 Tools/
├── .github/
│   ├── scripts/
│   │   └── verify_xposed.py
│   └── workflows/
│       └── manual-build.yml
├── README.md
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── xposed_init
│       ├── java/com/mt8163/tools/
│       │   ├── MainApplication.java
│       │   ├── core/
│       │   ├── modules/
│       │   │   ├── autorun/
│       │   │   ├── events/
│       │   │   ├── media/
│       │   │   ├── protection/
│       │   │   └── voltage/
│       │   ├── ui/
│       │   └── xposed/
│       │       ├── XposedEntry.java
│       │       ├── HookRegistry.java
│       │       ├── PackageHook.java
│       │       └── hooks/
│       │           ├── media/MediaHook.java
│       │           └── voltage/VoltageHook.java
│       └── res/
│           ├── drawable/
│           ├── layout/
│           └── values/
├── docs/
│   ├── PROJECT_STRUCTURE.md
│   ├── modules/
│   │   ├── events/README.md
│   │   └── voltage/README.md
│   └── xposed/
│       └── hooks/
│           ├── README.md
│           ├── autorun/README.md
│           ├── events/README.md
│           ├── media/README.md
│           ├── protection/README.md
│           └── voltage/README.md
├── gradle.properties
└── settings.gradle
```

The tree lists the current tracked project files and directories. The repository does not currently contain a Gradle wrapper (`gradlew`, `gradlew.bat`, or `gradle/wrapper/`).

## Root-level folders

### `.github/`

Contains GitHub automation:
- `scripts/verify_xposed.py` — validates Xposed-related project configuration.
- `workflows/manual-build.yml` — builds the debug APK on pushes to `development` and by manual dispatch.

### `app/`

The Android application module. It contains the manifest, Java source, Xposed entry asset, Android resources, and module-specific Gradle configuration.

### `docs/`

All project documentation belongs here. Keep documentation in English.
- `PROJECT_STRUCTURE.md` — current repository and package structure.
- `modules/` — feature-module documentation.
- `xposed/` — Xposed integration and hook documentation.

## Java package responsibilities

### `com.mt8163.tools`

- `MainApplication` initializes application preferences and the module registry, loads saved module states, initializes Events, and publishes the initial Voltage and Media configurations.

### `com.mt8163.tools.core`

Shared contracts and infrastructure:
- `ModuleContract` — common feature-module interface.
- `ModuleRegistry` — creates modules in display order, provides lookup, and loads saved enabled states.
- `PreferencesManager` — persists module enabled states.
- `EventLogger` — shared event logging entry point used by modules and hooks.

Core must not depend on individual feature implementations or UI components.

### `com.mt8163.tools.modules`

Feature modules are separated by responsibility:

| Package | Responsibility | Default state |
|---|---|---|
| `media` | Audio source and playback-state presentation/configuration | Disabled |
| `protection` | Protection module definition | Disabled |
| `voltage` | Voltage settings and module UI | Disabled |
| `autorun` | Autorun module definition | Disabled |
| `events` | Event storage, retrieval, and presentation | Enabled |

The module registry controls display order. Events receives shared log events; other feature modules and hooks must not depend on the Events UI or implementation.

### `com.mt8163.tools.ui`

Contains the main activity and shared UI components. UI code displays module state and interacts with module/Core interfaces. Xposed hook implementations do not belong here.

### `com.mt8163.tools.xposed`

Legacy XposedBridge integration:
- `XposedEntry` — implements `IXposedHookLoadPackage` and is registered through `assets/xposed_init`.
- `HookRegistry` — dispatches package-load callbacks to registered hooks and isolates installation failures.
- `PackageHook` — interface for package-specific hook implementations.
- `hooks/media/MediaHook` — observes firmware SourceService source and music callbacks in the Android system process.
- `hooks/voltage/VoltageHook` — Voltage hook implementation for the Android system process.

Each feature hook remains isolated in its own package.

## Media source monitoring

Stage 1 reads and presents only:
- the selected Audio Source package;
- the playback state reported by firmware `MusicInfo.state`.

`MediaHook` observes the firmware `SourceService.callManager` callbacks. A source callback updates the package and resets the displayed playback state to unknown until a music callback arrives. A music callback updates the raw playback state without replacing the selected package.

When the Media screen opens, the app requests a snapshot. The system-process hook reads `SourceInfo.getSourcePackage()` and `SourceInfo.getMusicInfo()`, then sends the values to the app through an explicit package broadcast. The UI receiver is registered only while the activity is resumed.

Raw playback values are preserved for diagnostics and mapped for display:
- `start` and `write` → `PLAYING`
- `paused` → `PAUSED`
- `stop` and `stopped` → `STOPPED`
- empty or unrecognized values → `UNKNOWN`

The hook does not replace `SourceInfo.OnEventListener`, issue playback commands, manage ACC transitions, or use the Bluetooth Binder service.

## Voltage configuration and process behavior

The application UI and the Android system process are separate processes. Voltage configuration is delivered using two mechanisms:

1. `VoltageSettingsStore` persists the UI settings and mirrors the hook configuration to the `voltage_hook_config` preferences.
2. It sends an explicit package broadcast to `android` containing the enabled state and voltage offset. The broadcast is protected by the app's signature-level configuration permission.
3. `VoltageHook` reads the initial values through `XSharedPreferences` and registers a receiver in the system process to accept subsequent configuration broadcasts.
4. The receiver updates the in-memory enabled state and offset. The installed hooks remain installed for the lifetime of the process; changing the module state controls whether voltage correction is applied, not whether the hooks are uninstalled.

The hook targets `android.carsource.McuService` and its battery packet callback. A zero-voltage MCU packet is not corrected.

## Xposed API and build configuration

The module uses the **Legacy XposedBridge API 82**, not Modern libxposed.

- `de.robv.android.xposed:api:82` is the compile-time Legacy API dependency and is declared as `compileOnly`.
- `xposedminversion=82` declares the minimum Xposed runtime API.
- `assets/xposed_init` registers the Legacy Xposed entry point.
- The declared LSPosed scope is `android`, matching the process targeted by the Media and Voltage hooks.
- Android `minSdk` and `targetSdk` are both 28. The project compiles against SDK 35.
- Java source and target compatibility are Java 17.

## Dependency direction

```text
UI ───────► Core ◄────── Feature modules
               ▲
               │
             Xposed
               │
       target-process hooks

Feature modules and hooks ───────► Core event logging
Events module ───────────────────► event storage and presentation
```

- UI depends on Core and module interfaces, not hook classes.
- Xposed hooks use shared infrastructure where appropriate and never call UI components.
- Feature modules do not depend on one another.
- Event producers use the shared Core logging entry point; they do not depend on the Events UI.
- Core remains independent of Android UI and feature-specific hook implementations.

## Platform constraints

- Android minimum and target SDK: API 28 (Android 9).
- Compile SDK: API 35.
- Java source/target compatibility: Java 17.
- UI target display: 1280×720 at 160 dpi.
- Use callbacks, listeners, broadcasts, and process lifecycle events instead of polling or timer-based state checks.
