# Hook implementations

Hooks are organized by MT8163 Tools functional module:

- `media/` — Media hooks.
- `protection/` — Protection hooks.
- `voltage/` — Voltage hooks.
- `autorun/` — Autorun hooks.
- `events/` — event-observation hooks.

Each independently registered hook must implement `com.mt8163.tools.xposed.PackageHook`, match its exact target package in `supports()`, and install against the target application's `classLoader`.

Keep hooks isolated. A failed hook must not prevent other hooks from being installed. Events is a consumer of Core events; other modules must never depend on Events.

Do not add guessed target package names or placeholder hook implementations. Add a hook only after its target and behavior are specified.
