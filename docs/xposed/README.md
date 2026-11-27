# Xposed Integration

## Framework

MT8163 Tools uses Legacy XposedBridge API 82 and runs under LSPosed on Android 9 (API 28).

The registered entry class is:

`com.mt8163.tools.xposed.XposedEntry`

It is declared in `app/src/main/assets/xposed_init` and receives package-load callbacks through `IXposedHookLoadPackage`.

## Package routing

`HookRegistry` routes callbacks to module hooks:

| Package / process | Registered hooks |
|---|---|
| `android` | Media, Voltage |
| `com.android.systemui` main process | Protection |

Protection checks both the package name and process name. Events and Autorun are not registered as Xposed hooks.

## Shared SystemServer bootstrap

`SystemBootstrap` hooks `SystemServer.startCoreServices()` and `SystemServer.startOtherServices()`. After either startup stage, it obtains the system context, registers `SystemConfigReceiver`, and retries module hook installation.

`SystemConfigReceiver` routes protected configuration broadcasts to Media and Voltage. It is shared infrastructure and is not removed when an individual feature module is disabled.

## Hook contract

Each module hook implements `PackageHook`:

- `supports(packageName)` — identifies its target package.
- `install(LoadPackageParam)` — loads configuration and installs required support/feature hooks.
- `moduleId()` — returns the module identifier used by `HookRegistry`.
- `uninstall()` — removes the module's active feature hooks and performs module-specific cleanup.

Hook implementations retain their `Unhook` handles. Support hooks and feature hooks have different lifetimes; see [Module Lifecycle](../architecture/dynamic-module-lifecycle.md).

## Module hook implementations

See the [hook inventory](hooks/README.md) for the current hook classes and their individual documentation.
