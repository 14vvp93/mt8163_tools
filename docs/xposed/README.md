# Xposed integration

Runtime source code lives in `app/src/main/java/com/mt8163/tools/xposed/`.
All architecture and implementation documentation lives in this root-level `docs/` directory.

## Runtime structure

- `XposedEntry` — legacy Xposed package-load entry point.
- `HookRegistry` — dispatches package-load events to matching hooks.
- `PackageHook` — contract for individual hook implementations.
- `hooks/` — hook implementations grouped by functional module.

## Framework

- Target framework: LSPosed 1.9.2.
- The LSPosed UI reports Xposed API 100.
- The current project integration uses the legacy Xposed API 82 compile artifact.
- Xposed API classes must be declared with Gradle `compileOnly`; they must not be packaged in the APK.
- Legacy entry point registration uses `app/src/main/assets/xposed_init`.

The framework's displayed Xposed API number is not the version of the legacy compile artifact.

## Rules

- Keep hook implementation code in the app source tree, not in `docs/`.
- Keep documentation exclusively under the repository-root `docs/` directory.
- Register hooks by exact target package and use that package's class loader.
- Isolate hook failures so one hook cannot prevent unrelated hooks from loading.
- Do not invent target packages or implement hooks before their target and behavior are specified.
- Module enablement must be checked using state shared with the target process; the UI process's in-memory state is not sufficient.
- Events consumes Core events. Other modules must not depend on Events.
