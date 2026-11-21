# Xposed hooks

Package: `com.mt8163.tools.xposed`

All LSPosed hooks and their registration belong in this package.

## Files

- `XposedEntry` — legacy Xposed package-load entry point.
- `HookRegistry` — dispatches package-load events to matching hooks.
- `PackageHook` — contract for individual hook implementations.
- `hooks/` — target-specific hook classes.

## Runtime

- Framework: LSPosed 1.9.2 (framework reports Xposed API 100).
- Compile API: legacy Xposed API 82.
- Device target: Android 9 (API 28).

The framework supplies the Xposed API at runtime. Gradle must use `compileOnly`; never package Xposed API classes in the APK.

The entry point is registered in `app/src/main/assets/xposed_init`. Set `xposedminversion` to 82 because this module compiles against legacy API 82; the framework's displayed API 100 is not the library version.

Each hook must implement `PackageHook`, match its target package, and use the target package's `classLoader`. Keep each hook isolated so one failure does not block other hooks.

No target-specific hooks are registered yet.
