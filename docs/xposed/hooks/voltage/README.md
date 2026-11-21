# Voltage

## Purpose

Voltage is an MT8163 Tools feature module that applies a configurable offset to the software-reported battery voltage in Android.

It changes only `McuService.mBatteryVoltage`. It does not change the MCU measurement, physical voltage, charging behavior, or the original low-voltage protection logic.

The module is disabled by default.

## Module UI

Voltage is presented through the application's existing module screen.

The controls appear in this order:

1. **Module Enabled** — enables or disables voltage correction.
2. **Voltage Offset** — selects an offset from `-2.0 V` to `+2.0 V` in `0.1 V` steps. Default: `0.0 V`.
3. **Reset** — sets the offset to `0.0 V` without changing Module Enabled.

The selected offset remains saved while the module is disabled. When disabled, the hook uses the unmodified raw voltage.

The module is registered through the existing Core module architecture:

- Module ID: `voltage`
- Module class: `com.mt8163.tools.modules.voltage.VoltageModule`
- Layout resource: `R.layout.module_voltage`
- Module contract: `com.mt8163.tools.core.ModuleContract`
- Registration and ordering: `com.mt8163.tools.core.ModuleRegistry`

The Voltage layout is the UI entry point for these controls. UI code uses Core/module interfaces and does not call Xposed hook classes.

## Settings and Configuration

The application stores the module enabled state through the shared module-state mechanism. The offset is stored as a `Float` in the Voltage settings:

```text
Preference file: voltage_settings
Key:            voltage_offset
Default:        0.0
```

The app publishes the complete Voltage configuration to the Android process:

```text
Action:  com.mt8163tools.action.VOLTAGE_CONFIG_CHANGED
Package: android

Extras:
  module_enabled: boolean
  voltage_offset: float
```

The complete configuration is published:

- After the application loads its saved settings.
- Whenever Module Enabled changes.
- Whenever Voltage Offset changes or Reset is pressed.

The hook also loads its initial configuration from the dedicated `voltage_hook_config` cross-process preference store. On Android 9, the agreed implementation uses `MODE_WORLD_READABLE`, with a `MODE_PRIVATE` fallback if the readable mode cannot be created. Runtime broadcasts update the hook's in-memory configuration.

The app-side publisher and hook-side receiver must use the same action, package, extra names, preference file, and keys.

## Xposed Integration

Voltage hook code belongs to:

```text
app/src/main/java/com/mt8163/tools/xposed/hooks/voltage/
```

It follows the existing `PackageHook` / `HookRegistry` integration. The hook targets the `android` package, which runs the Android `system_server` process.

| Target | Value |
|---|---|
| Class | `android.carsource.McuService` |
| Constructor | `McuService(Context)` |
| Method | `onBattery(McuPacket)` |
| Descriptor | `(Landroid/carsource/McuService$McuPacket;)V` |
| Hook timing | After-hook |
| Field updated | `McuService.mBatteryVoltage` |
| LSPosed scope | `android` |

The configuration receiver is registered with the `McuService` context after the service constructor.

## Voltage Processing

The original `onBattery()` method executes first and remains unchanged. Voltage runs after it, so the original MCU processing and low-voltage/reboot logic receive the real MCU data.

Only battery packets are processed:

- `subCmd() == 3`
- `paramLen() >= 1`
- Voltage byte: `param(0)`

The byte is unsigned and represents tenths of a volt:

```text
rawVoltage = (parameter & 0xFF) / 10.0
```

For example, `0x78` represents `12.0 V`.

The hook retains the decoded value separately as `rawBatteryVoltage`. It calculates the field value from that raw value:

```text
if module_enabled:
    effectiveVoltage = rawBatteryVoltage + voltage_offset
else:
    effectiveVoltage = rawBatteryVoltage
```

The offset is never added to a previously corrected value.

When a valid battery packet arrives, the hook stores the new raw voltage and updates `mBatteryVoltage`. When configuration changes, it recalculates `mBatteryVoltage` from the retained raw value, if available. Disabling the module restores the raw value. Reset sets the offset to zero and leaves the enabled state unchanged.

## Runtime Rules

- Packet updates and configuration updates use the same lock.
- Accept only finite offsets within `-2.0..+2.0 V`.
- Invalid or incomplete packet data must not overwrite the last valid voltage.
- Invalid configuration must not replace the last valid configuration.
- The packet-processing path uses in-memory configuration and performs no disk I/O.
- Do not use polling, timers, worker threads, executors, or recurring callbacks.
- A UI state change updates the running hook through the configuration broadcast; it does not reinstall the hook.

## Scope

Voltage has one function: apply the configured offset to the software-reported value in `McuService.mBatteryVoltage`, after the original MCU service method has completed.
