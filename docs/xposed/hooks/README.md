# Hook organization

Hooks are grouped by MT8163 Tools functional module. The corresponding Java packages are under `app/src/main/java/com/mt8163/tools/xposed/hooks/`.

- `media/` — Media hooks.
- `protection/` — Protection hooks.
- `voltage/` — Voltage hooks.
- `autorun/` — Autorun hooks.
- `events/` — event-observation hooks.

Each independently registered hook implements `com.mt8163.tools.xposed.PackageHook`, matches its exact target package, and installs against the target application's class loader.

Keep hooks independent. A failure in one hook must not stop other hooks. Events receives events through Core's neutral interface; other modules must never depend on Events.
