# Voltage Xposed Hook

## Purpose

The hook applies the Voltage module's offset to the battery voltage reported by Android. It modifies only `McuService.mBatteryVoltage`; the MCU packet, physical measurement, charging behavior, and original low-voltage protection logic remain unchanged.

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

The constructor hook and battery-method hook are installed independently. Failure to resolve the packet class or install `onBattery` does not prevent the constructor hook from being installed. Hook installation and configuration-receiver registration are diagnosed separately.

## MCU Processing

The implementation was checked against the decompiled source in [public_worker/artifacts/CarServises](https://github.com/14vvp93/public_worker/tree/main/artifacts/CarServises).

`processMCUCommand()` dispatches MCU command `55` to `onBattery(McuPacket)`. The method processes battery data when `subCmd() == 3` and reads the first parameter as an unsigned byte in tenths of a volt:

```text
rawVoltage = (param(0) & 0xFF) / 10.0
```

The original method updates low-battery state, assigns `mBatteryVoltage`, and evaluates the original reboot threshold before the after-hook runs. The default reboot threshold is `5.5 V`.

`McuService.getBattery()` returns `mBatteryVoltage`. The value is forwarded through `McuManager.getBattery()` and is also read by `CarStatusService` and SystemUI.

## Voltage Processing

For each battery packet with a valid subcommand and parameter, the hook retains the decoded raw value and calculates the reported value from that raw value:

```text
if module_enabled:
    outputVoltage = rawVoltage + voltage_offset
else:
    outputVoltage = rawVoltage
```

The offset is never added to a previously corrected value. Configuration changes recalculate the field from the retained raw voltage. A raw value of `0.0 V` is accepted and replaces the previous raw value; it is not treated as missing data.

No output-voltage clamp is applied. The offset is finite and limited to `-2.0..+2.0 V` by the module settings.

## Configuration

The hook loads the initial configuration through Legacy `XSharedPreferences` and registers a dynamic receiver for subsequent updates.

- Preferences file: `voltage_hook_config`
- Action: `com.mt8163.tools.action.VOLTAGE_CONFIG_CHANGED`
- Target package: `android`
- Permission: `com.mt8163.tools.permission.UPDATE_VOLTAGE_CONFIG`
- Fields: `module_enabled` (boolean), `voltage_offset` (finite float)

The receiver requires the app's signature-level permission. The broadcast does not rely on sender-UID APIs or a session-token handshake. The initial preferences-loaded flag is set only after a successful read. If the first read fails, service initialization can retry it.

## Runtime Rules

- Packet processing and configuration updates use the same lock.
- Invalid offsets do not replace the last valid configuration.
- The original low-voltage and reboot logic runs before the after-hook and is not changed.
- Packet processing performs no disk I/O.
- No polling, timers, worker threads, executors, or recurring callbacks are used.
- Configuration changes update the running hook through a broadcast; the hook is not reinstalled.

## Diagnostics

Hook lifecycle diagnostics distinguish:

- Constructor and `onBattery` hook installation success or failure.
- Initial configuration load and update validation.
- Configuration receiver registration success or failure.
- First battery packet received.
- First successful update of `mBatteryVoltage`.
- Voltage correction and field-update failures.

Voltage submits operational events through the Core `EventLogger` interface. The Events module is a consumer; the hook does not depend on Events storage or UI.
