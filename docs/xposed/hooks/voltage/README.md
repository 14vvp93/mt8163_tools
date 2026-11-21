# Voltage

## Purpose

Voltage applies a configurable offset to the software-reported battery voltage in Android.

It changes only `McuService.mBatteryVoltage`. It does not change the MCU measurement, physical voltage, charging behavior, or the original low-voltage protection logic.

The module is disabled by default.

## Module UI

The Voltage screen contains:

1. **Module Enabled** — the module switch in the shared module header.
2. **Voltage Offset** — a slider from `-2.0 V` to `+2.0 V` in `0.1 V` steps. Default: `0.0 V`.
3. **Reset** — sets the offset to `0.0 V` without changing the module enabled state.

The selected offset remains saved while the module is disabled.

## Module Integration

- Module ID: `voltage`
- Module class: `com.mt8163.tools.modules.voltage.VoltageModule`
- Layout: `R.layout.module_voltage`
- Module contract: `com.mt8163.tools.core.ModuleContract`
- Registry: `com.mt8163.tools.core.ModuleRegistry`
- Hook class: `com.mt8163.tools.xposed.hooks.voltage.VoltageHook`
- LSPosed scope: `android`

The UI stores the offset in the private `voltage_settings` preference file under `voltage_offset`. The enabled state uses the existing `mt8163_tools` module-state preference.

## Configuration Protocol

The hook registers its receiver after the `McuService(Context)` constructor and sends one explicit configuration request to the MT8163 Tools package.

Actions:

- `com.mt8163.tools.action.VOLTAGE_CONFIG_REQUEST`
- `com.mt8163.tools.action.VOLTAGE_CONFIG_RESPONSE`
- `com.mt8163.tools.action.VOLTAGE_CONFIG_CHANGED`

All messages contain a 64-character hexadecimal session token. The hook creates a new 256-bit token for each request. The app stores the token and includes it in the response and subsequent updates.

The app request receiver is exported and protected by `android.permission.DUMP`, so ordinary applications cannot trigger it. Responses and updates are explicitly addressed to package `android`; the hook accepts them only when the session token matches.

Configuration extras:

- `session_token`: session token
- `module_enabled`: boolean
- `voltage_offset`: float in the range `-2.0..+2.0`

The hook starts with correction disabled. It enables configuration only after receiving a valid response. If no app response is available, the original battery voltage remains unchanged.

No cross-process preferences, `MODE_WORLD_READABLE`, or `XSharedPreferences` are used.

## Xposed Target

| Target | Value |
|---|---|
| Package | `android` |
| Class | `android.carsource.McuService` |
| Constructor | `McuService(Context)` |
| Method | `onBattery(McuPacket)` |
| Descriptor | `(Landroid/carsource/McuService$McuPacket;)V` |
| Hook timing | After-hook |
| Field updated | `McuService.mBatteryVoltage` |

The hook leaves the original method and MCU packet unchanged.

## Verified MCU Processing

The implementation was checked against the decompiled source in [public_worker/artifacts/CarServises](https://github.com/14vvp93/public_worker/tree/main/artifacts/CarServises).

`McuService.onBattery(McuPacket)` processes battery data when `subCmd() == 3`. It reads the first parameter as an unsigned byte in tenths of a volt:

```text
rawVoltage = (param(0) & 0xFF) / 10.0
```

The original method updates low-battery state, assigns `mBatteryVoltage`, and evaluates the original reboot threshold. The default reboot threshold is `5.5 V`. This original logic executes before the Voltage after-hook.

`McuService.getBattery()` returns `mBatteryVoltage`, which is forwarded to clients through `McuManager.getBattery()`.

## Voltage Processing

For each valid battery packet, the hook retains the raw decoded voltage and calculates the reported value from that raw value:

```text
if module_enabled:
    effectiveVoltage = rawVoltage + voltage_offset
else:
    effectiveVoltage = rawVoltage
```

The offset is never added to a previously corrected value. When configuration changes, the hook recalculates the field from the retained raw voltage.

## Runtime Rules

- Packet and configuration updates use the same lock.
- Only finite offsets within `-2.0..+2.0 V` are accepted.
- Invalid packet data does not replace the last valid raw voltage.
- Invalid configuration does not replace the last valid configuration.
- Packet processing performs no disk I/O.
- No polling, timers, worker threads, executors, or recurring callbacks are used.
- UI changes update the running hook through `VOLTAGE_CONFIG_CHANGED`; the hook is not reinstalled.

## Events Integration

Voltage submits events through the Core `EventLogger` interface. The Events module stores and displays them when it is enabled.

Reported sources include:

- `moduleEnabled` and `moduleDisabled`
- `targetClassFound`, `targetMethodFound`, `hookInstalled`, and `hookFailed`
- `configurationLoaded`, `configurationChanged`, and `invalidConfiguration`
- `settingsChanged`
- `voltageInvalid`, `hookException`, and `correctionFailed`
- `voltageCorrected`

`voltageCorrected` is emitted only when the effective corrected output differs from the last reported effective output and from the raw MCU voltage. Repeated identical output values are not logged. The comparison does not use `mBatteryVoltage` as the previous value because the original `onBattery()` method writes the raw value before the after-hook runs. Raw packet values are not logged during normal operation.
