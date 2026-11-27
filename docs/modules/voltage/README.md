# Voltage Module

## Purpose

Voltage applies a configurable offset to the battery voltage reported by Android. It changes the software-reported value only; it does not modify the MCU measurement, physical voltage, charging behavior, or the original low-voltage protection logic.

The module is disabled by default.

## UI

The Voltage screen contains:

1. **Module Enabled** — the switch in the shared module header.
2. **Voltage Offset** — a slider from `-2.0 V` to `+2.0 V` in `0.1 V` steps. Default: `0.0 V`.
3. **Reset** — sets the offset to `0.0 V` without changing the enabled state.

The selected offset remains saved while the module is disabled. A zero offset means the reported value remains equal to the raw MCU value.

## Module integration

- Module ID: `voltage`.
- Module class: `com.mt8163.tools.modules.voltage.VoltageModule`.
- Layout: `R.layout.module_voltage`.
- Module contract: `com.mt8163.tools.core.ModuleContract`.
- Hook: [Voltage Xposed Hook](../../xposed/hooks/voltage/README.md).
- LSPosed scope: `android`.

The UI stores the offset in the private `voltage_settings` preferences under `voltage_offset`. The enabled state is stored by the shared module-state preferences.

## Runtime configuration

The application writes the current hook configuration to `voltage_hook_config` and sends a package-targeted broadcast. The shared SystemServer `SystemConfigReceiver` forwards updates to `VoltageHook`.

- Action: `com.mt8163.tools.action.VOLTAGE_CONFIG_CHANGED`.
- Target package: `android`.
- Permission: `com.mt8163.tools.permission.UPDATE_VOLTAGE_CONFIG`.
- Fields: `module_enabled` (boolean) and `voltage_offset` (finite float in `-2.0..+2.0 V`).

The hook reads its initial configuration through Legacy `XSharedPreferences`. Subsequent changes are delivered by the shared receiver; VoltageHook does not register a separate broadcast receiver.

## Event logging

Voltage submits operational events through Core's `EventLogger`. The Events module stores and displays them when enabled. Voltage does not depend on Events storage or UI.
