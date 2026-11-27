# Xposed Hooks

Xposed implementations are stored separately from application module code under:

`app/src/main/java/com/mt8163/tools/xposed/hooks/`

Only modules with active Xposed integrations have a hook package.

| Package | Hook class | Target process | Documentation |
|---|---|---|---|
| `media/` | `MediaHook` | `android` | [Media](media/README.md) |
| `protection/` | `ProtectionHook` | `com.android.systemui` main process | [Protection](protection/README.md) |
| `voltage/` | `VoltageHook` | `android` | [Voltage](voltage/README.md) |

Events has no Xposed hook. It receives operational events through the Core `EventLogger` API. Autorun has no hook implementation.

See [Xposed integration](../README.md) for package routing, shared bootstrap, and configuration delivery.
