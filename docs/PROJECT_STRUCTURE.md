# Project structure

This document describes the repository layout and the responsibility of each layer. It is maintained in the repository-root `docs/` directory; application source folders contain code and Android resources only.

## Repository layout

```text
MT8163 Tools/
├── docs/
│   ├── PROJECT_STRUCTURE.md
│   ├── modules/
│   │   ├── voltage/
│   │   └── events/
│   └── xposed/
│       ├── README.md
│       └── hooks/
│           ├── README.md
│           ├── media/
│           ├── protection/
│           ├── voltage/
│           ├── autorun/
│           └── events/
│
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
│       │   │   ├── media/
│       │   │   ├── protection/
│       │   │   ├── voltage/
│       │   │   ├── autorun/
│       │   │   └── events/
│       │   ├── ui/
│       │   └── xposed/
│       │       ├── XposedEntry.java
│       │       ├── HookRegistry.java
│       │       ├── PackageHook.java
│       │       └── hooks/
│       │           ├── media/
│       │           ├── protection/
│       │           ├── voltage/
│       │           ├── autorun/
│       │           └── events/
│       └── res/
│           ├── layout/
│           ├── drawable/
│           └── values/
│
├── gradle/
├── gradle.properties
├── gradlew
├── gradlew.bat
└── settings.gradle
```

The tree is a logical map of the current architecture. Some module-specific hook packages are reserved for implementation and may contain no Java classes yet. Android/Gradle wrapper files are shown only where present in the repository.

## Root-level folders

### `docs/`

All project documentation belongs here. Do not put Markdown documentation in `app/src/main/`.

- `PROJECT_STRUCTURE.md` — repository and package architecture.
- `modules/` — feature-module behavior, UI, configuration and integration.
- `xposed/` — LSPosed integration design, API notes, hook organization and implementation rules.

### `app/`

The Android application module. Contains the Android manifest, Java source, app assets, resources and module-specific Gradle configuration.

## Java package responsibilities

### `com.mt8163.tools`

Application entry point and application-wide initialization.

- `MainApplication` initializes the module registry and preferences.

### `com.mt8163.tools.core`

Shared contracts and infrastructure. This layer must not depend on individual feature implementations.

- `ModuleContract` — common module contract.
- `ModuleRegistry` — creates modules in their defined display order and provides lookup/state loading.
- `PreferencesManager` — persists module enablement preferences.
- Shared event contracts/services belong here so producers do not depend on the Events module.

### `com.mt8163.tools.modules`

Feature-level module definitions and business logic, separated by feature:

| Package | Responsibility | Default state |
|---|---|---|
| `media` | Media functionality | Disabled |
| `protection` | Protection functionality | Disabled |
| `voltage` | Voltage functionality | Disabled |
| `autorun` | Autorun functionality | Disabled |
| `events` | Event log and presentation | Enabled |

The Events module consumes events through Core. Other feature modules must never depend on Events.

### `com.mt8163.tools.ui`

Activity and UI components. UI displays module state and calls module/Core interfaces. It must not contain Xposed hook implementations.

### `com.mt8163.tools.xposed`

Legacy XposedBridge integration layer, isolated from UI:

- `XposedEntry` — framework entry point registered through `assets/xposed_init`.
- `HookRegistry` — dispatches package-load callbacks to applicable hooks.
- `PackageHook` — interface implemented by individual package hooks.
- `hooks/<module>/` — hook implementations grouped by feature module.

Hook code must use the target process's package name and class loader. A hook failure must be isolated and logged; it must not prevent unrelated hooks from loading.

## Xposed API and build configuration

The module requires **Xposed runtime API 93 or newer**, declared by `xposedminversion=93` in `AndroidManifest.xml`.

The source uses the **Legacy XposedBridge API**, not Modern libxposed. The Gradle dependency `de.robv.android.xposed:api:82` is the compile-time API artifact used to resolve the legacy classes and method signatures. It is declared with `compileOnly`: the framework supplies these classes at runtime, and they must never be packaged in the APK.

These values have different roles:

- `xposedminversion=93` — minimum Xposed runtime API required to load the module.
- `compileOnly de.robv.android.xposed:api:82` — compile-time Legacy API artifact.
- `minSdk 28` / `targetSdk 28` — Android platform SDK levels; unrelated to Xposed API versions.

The entry point remains `IXposedHookLoadPackage` and is registered through `assets/xposed_init`. Do not replace it with Modern libxposed entry points or `META-INF/xposed` metadata as part of this configuration.

The declared LSPosed scope is `android`, matching the Android system process targeted by the Voltage hook.

## Module state and process boundaries

The UI process and hooked target processes are separate Android processes. An in-memory boolean in `MainApplication` is not a reliable source of module enablement inside target processes.

Before implementing feature hooks, define a process-safe state delivery mechanism (for example, validated XSharedPreferences or another explicitly designed IPC mechanism). Hook installation must read that state in the target process. Do not claim that changing a UI switch dynamically removes hooks already installed in a running process; hook installation generally occurs at package load, so changes take effect when the relevant process is started again unless a separate runtime control mechanism is implemented.

## Dependency direction

```text
UI ───────► Core ◄────── Modules
               ▲
               │
             Xposed
               │
       target-process hooks

Events ◄──── Core event interface
```

- UI depends on Core/module contracts, not on hook classes.
- Xposed hooks use Core contracts where appropriate; they do not call UI components.
- Feature modules do not depend on one another.
- Events is an event consumer, not a dependency of event producers.
- Core must remain independent of Android UI and feature-specific hook implementations.

## Platform constraints

- Minimum and target Android version: Android 9 (API 28).
- Java source/target compatibility: Java 17.
- UI target resolution: 1280×720 at 160 dpi.
- Avoid polling and timer-based state checks; use callbacks, listeners, broadcasts or explicit process lifecycle events.
