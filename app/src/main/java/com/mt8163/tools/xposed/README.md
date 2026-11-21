# Xposed hooks

Package: `com.mt8163.tools.xposed`

All LSPosed hook entry points, hook registration, and individual hook implementations belong in this package.

## Structure

- `XposedEntry` — modern libxposed module entry point.
- `HookRegistry` — registers hooks after the framework loads the module.
- `hooks/` — individual hooks grouped by target component.

## Runtime

Target framework: LSPosed 1.9.2 (Xposed API 100), Android 9 (API 28).

Use the modern libxposed API 100 only. Do not mix it with the legacy `de.robv.android.xposed` API. Hook implementations must be registered from the module lifecycle callback, not during class initialization.

No target-specific hooks are added until their target package and behavior are defined.
