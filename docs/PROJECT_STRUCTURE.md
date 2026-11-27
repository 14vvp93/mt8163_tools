# Project structure

This document describes the implemented repository structure and dependency boundaries.

## Repository layout

~~~text
MT8163 Tools/
├── .github/workflows/
│   ├── ci.yml
│   ├── html-preview.yml
│   └── manual-build.yml
├── app/
│   ├── build.gradle
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/xposed_init
│       │   ├── java/com/mt8163/tools/
│       │   │   ├── MainApplication.java
│       │   │   ├── core/
│       │   │   ├── modules/
│       │   │   │   ├── ModuleCatalog.java
│       │   │   │   ├── ModuleUiContract.java
│       │   │   │   ├── autorun/
│       │   │   │   ├── events/
│       │   │   │   ├── media/
│       │   │   │   ├── protection/
│       │   │   │   └── voltage/
│       │   │   ├── ui/
│       │   │   └── xposed/
│       │   │       └── hooks/
│       │   └── res/
│       └── test/java/com/mt8163/tools/modules/
├── docs/
│   ├── architecture/
│   ├── modules/
│   └── xposed/
├── preview/index.html
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
├── gradle.properties
└── settings.gradle
~~~

All project documentation belongs under the repository-root docs/ directory and is written in English.

## Core

com.mt8163.tools.core contains shared contracts and infrastructure:

- ModuleContract — module identity, title/layout resources, and enabled state.
- ModuleRegistry — stores and exposes the supplied module definitions; it does not instantiate or import feature implementations.
- PreferencesManager — persists module enabled states.
- EventLogger — shared event submission interface.

Core does not construct concrete feature modules or reference module UI classes.

## Feature modules

Each module owns its settings, runtime implementation, UI binding, and Xposed hook package where applicable.

ModuleCatalog is the application composition root. It creates modules in sidebar order and injects them into ModuleRegistry.

ModuleUiContract lets a module create its own content view and handle its enabled transition, content cleanup, and activity results. MainActivity depends on this shared interface rather than importing individual module view classes or dispatching by module ID.

| Module | UI/runtime package | Hook package | State |
|---|---|---|---|
| Media | modules/media | xposed/hooks/media | Implemented; device validation pending |
| Protection | modules/protection | xposed/hooks/protection | Implemented; device validation pending |
| Voltage | modules/voltage | xposed/hooks/voltage | Implemented; device validation pending |
| Events | modules/events | xposed/hooks/events | Event storage and UI implemented |
| Autorun | modules/autorun | Reserved | Placeholder only; outside the current work package |

Feature modules do not depend on one another. Event producers use Core's EventLogger; they do not depend on Events storage or UI.

## UI

com.mt8163.tools.ui contains the activity shell, shared module header, sidebar adapter, and dialog helpers. It owns navigation and the common module contract, not individual module layouts or behavior.

Module views are inflated and bound by their owning module through ModuleUiContract. Visual properties remain in Android resources.

## Xposed integration

The project uses Legacy XposedBridge API 82.

- XposedEntry is the registered Legacy entry point.
- HookRegistry dispatches package-load callbacks to module hooks.
- PackageHook defines package support, installation, and uninstall operations.
- Feature hooks remain isolated under xposed/hooks/<module>/.

Target packages:
- android — Media and Voltage.
- com.android.systemui — Protection.

## Runtime configuration

The application process persists settings and publishes module-specific configuration to the target process using explicit package broadcasts protected by signature-level permissions. Each hook reads its initial configuration and handles subsequent changes in its own process.

Static fields are process-local; application-process state must not be treated as shared memory with system_server or SystemUI.

## Build and verification

- Android min/target SDK: 28.
- Compile SDK: 35.
- Java source/target: 17.
- Android Gradle Plugin: 8.7.3.
- Gradle: 8.9.
- CI runs lint and unit tests only.
- APK Build is manual-only.
- Gradle Wrapper is pinned to Gradle 8.9.

## Device constraints

- Android 9 / API 28.
- 1280×720 at 160 dpi (mdpi).
- Keep UI content within the target display without unnecessary scrolling.
- Use event-driven callbacks, listeners, broadcasts, and lifecycle events; do not add polling.
- Runtime behavior involving vendor Binder services, MCU fields, MediaSession, LSPosed preferences, and SystemUI internals must be validated on the actual head unit.
