# Protection hooks

Place Protection module hook classes in this package.

- Keep protection-specific target package names and hook installation here.
- Implement `com.mt8163.tools.xposed.PackageHook` for each independently registered hook.
- Do not depend on other module hook packages.
- Send diagnostic events through the Core event interface when it is available.

No target package or hook is assumed until the device-specific behavior is defined.
