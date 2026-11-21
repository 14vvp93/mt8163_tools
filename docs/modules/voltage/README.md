# Voltage Module

## Purpose

Voltage applies a configurable offset to the battery voltage reported by Android. The module changes the software-reported value only; it does not modify the MCU measurement, physical voltage, charging behavior, or the original low-voltage protection logic.

The module is disabled by default.

## Module UI

The Voltage screen contains:

1. **Module Enabled** — the module switch in the shared module header.
2. **Voltage Offset** — a slider from `-2.0 V` to `+2.0 V` in `0.1 V` steps. Default: `0.0 V`.
3. **Reset** — sets the offset to `0.0 V` without changing the enabled state.

The selected offset remains saved while the module is disabled.

## Module Integration

- Module ID: `voltage`
- Module class: `com.mt8163.tools.modules.voltage.VoltageModule`
- Layout: `R.layout.module_voltage`
- Module contract: `com.mt8163.tools.core.ModuleContract`
- Registry: `com.mt8163.tools.core.ModuleRegistry`
- Hook implementation: [Voltage Xposed Hook](../../xposed/hooks/voltage/README.md)
- LSPosed scope required by the hook: `android`

The UI stores the offset in the private `voltage_settings` preference file under `voltage_offset`. The enabled state uses the shared `mt8163_tools` module-state preference.

## Runtime Configuration

The app and the Android system process exchange configuration through explicit broadcasts. The hook requests the current configuration when its target service becomes available. The app responds with the enabled state and offset; subsequent UI changes are published to the running hook.

Configuration actions:

- `com.mt8163.tools.action.VOLTAGE_CONFIG_REQUEST`
- `com.mt8163.tools.action.VOLTAGE_CONFIG_RESPONSE`
- `com.mt8163.tools.action.VOLTAGE_CONFIG_CHANGED`

Messages use a 64-character hexadecimal session token to associate responses and updates with the active hook session. The hook starts with correction disabled and accepts configuration only after validating the response. If no valid response arrives, the original reported voltage remains unchanged.

Configuration fields:

- `session_token` — session identifier.
- `module_enabled` — boolean module state.
- `voltage_offset` — finite float in the range `-2.0..+2.0`.

No cross-process preferences, `MODE_WORLD_READABLE`, or `XSharedPreferences` are used.

## Event Logging

Voltage reports operational events through the Core `EventLogger` interface. The Events module stores and displays these records when it is enabled. Voltage does not depend on the Events module's storage or UI.

See [Voltage Xposed Hook](../../xposed/hooks/voltage/README.md) for hook lifecycle, processing rules, and emitted event sources.
