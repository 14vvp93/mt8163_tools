# Voltage Xposed Hook

## Purpose

The hook applies the Voltage module's validated configuration to the battery voltage reported by Android. It modifies only `McuService.mBatteryVoltage`; the MCU packet, physical measurement, charging behavior, and original low-voltage protection logic remain unchanged.

The module-level UI, preferences, and configuration contract are documented in [Voltage Module](../../../modules/voltage/README.md).

## Registration and Target

- Hook class: `com.mt8163.tools.xposed.hooks.voltage.VoltageHook`
- Target package / LSPosed scope: `android`
- Target class: `android.carsource.McuService`
- Constructor: `McuService(Context)`
- Method: `onBattery(McuPacket)`
- Method descriptor: `(Landroid/carsource/McuService$McuPacket;)V`
- Hook timing: after-hook
- Updated field: `McuService.mBatteryVoltage`

The hook is registered through the Xposed package-hook registry. Hook installation and configuration-receiver registration are separate operations and are diagnosed independently. Installation is guarded against repeated attempts in the same process. A failed hook installation must not prevent unrelated package hooks from loading.

## MCU Processing

The implementation was checked against the decompiled source in [public_worker/artifacts/CarServises](https://github.com/14vvp93/public_worker/tree/main/artifacts/CarServises).

`McuService.onBattery(McuPacket)` processes battery data when `subCmd() == 3`. It reads the first parameter as an unsigned byte in tenths of a volt:

```text
rawVoltage = (param(0) & 0xFF) / 10.0
```

The original method updates low-battery state, assigns `mBatteryVoltage`, and evaluates the original reboot threshold. The default reboot threshold is `5.5 V`. The original logic executes before this after-hook.

`McuService.getBattery()` returns `mBatteryVoltage`, which is forwarded to clients through `McuManager.getBattery()`.

## Voltage Processing

For each valid battery packet, the hook retains the decoded raw voltage and calculates the reported value from that raw value:

```text
if module_enabled:
    effectiveVoltage = rawVoltage + voltage_offset
else:
    effectiveVoltage = rawVoltage
```

The offset is never added to a previously corrected value. When configuration changes, the hook recalculates the field from the retained raw voltage.

The encoded MCU value is an unsigned byte in tenths of a volt. The representable output range is therefore `0.0..25.5 V`. A corrected value outside this range is limited to the representable range and reported diagnostically.

## Runtime Rules

- Packet processing and configuration updates use the same lock.
- Only finite offsets within `-2.0..+2.0 V` are accepted.
- Invalid packet data does not replace the last valid raw voltage.
- Invalid configuration does not replace the last valid configuration.
- Configuration messages are validated, including their session token and field types.
- Session tokens are compared using `MessageDigest.isEqual`.
- Packet processing performs no disk I/O.
- No polling, timers, worker threads, executors, or recurring callbacks are used.
- Configuration changes update the running hook through a broadcast; the hook is not reinstalled.

## Diagnostics and Events

Hook lifecycle diagnostics distinguish:

- Target class and method discovery.
- Hook installation success or failure.
- Configuration receiver registration success or failure.
- Configuration load, update, and validation failures.

Voltage submits operational events through the Core `EventLogger` interface. The Events module is a consumer; the hook does not depend on Events storage or UI.

Event sources include:

- `moduleEnabled` and `moduleDisabled`
- `targetClassFound`, `targetMethodFound`, `hookInstalled`, and hook failure diagnostics
- `receiverRegistered` and receiver registration failure diagnostics
- `configurationLoaded`, `configurationChanged`, and `invalidConfiguration`
- `settingsChanged`
- `voltageInvalid`, `hookException`, and `correctionFailed`
- `voltageOutputLimited`
- `voltageCorrected`

`voltageCorrected` is emitted only when the effective output changes and differs from the raw MCU voltage. Repeated identical output values are not logged. The comparison does not use `mBatteryVoltage` as the previous value because the original `onBattery()` method writes the raw value before the after-hook runs. Raw packet values are not logged during normal operation.
