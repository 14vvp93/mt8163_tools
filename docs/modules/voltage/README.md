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

The UI stores the offset in the private `voltage_settings` preference file under `voltage_offset`. The enabled state uses the `mt8163_tools` module-state preference.

## Runtime Configuration

The app and Android system process exchange configuration through an explicit package-targeted broadcast. The app also writes the current hook configuration to the `voltage_hook_config` preferences file. The hook reads that file during initialization and receives subsequent changes through the broadcast.

- Action: `com.mt8163.tools.action.VOLTAGE_CONFIG_CHANGED`
- Target package: `android`
- Permission: `com.mt8163.tools.permission.UPDATE_VOLTAGE_CONFIG`
- Fields: `module_enabled` (boolean) and `voltage_offset` (finite float in `-2.0..+2.0 V`)

The receiver requires a signature-level permission owned by MT8163 Tools. No session-token request/response protocol is used.

## Event Logging

Voltage reports operational events through the Core `EventLogger` interface. The Events module stores and displays these records when it is enabled. Voltage does not depend on the Events module's storage or UI.

See [Voltage Xposed Hook](../../xposed/hooks/voltage/README.md) for hook lifecycle, processing rules, and emitted event sources.
