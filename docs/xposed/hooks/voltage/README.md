# Voltage

## Purpose

Voltage adjusts the software-reported battery voltage that Android receives from the head unit's MCU.

- Only the value stored by the Android MCU service is modified.
- Physical voltage, MCU measurements, and charging behavior are not changed.
- The module is disabled by default.

## Hook target

| Parameter | Value |
|---|---|
| Process / package | `android` (`system_server`) |
| Service | `android.carsource.McuService` |
| Constructor | `McuService(Context)` |
| Method | `onBattery(McuPacket)` |
| Descriptor | `(Landroid/carsource/McuService$McuPacket;)V` |
| Hook type | LSPosed after-hook |
| Destination field | `McuService.mBatteryVoltage` |
| LSPosed scope | `android` |

The hook runs **after the original** `onBattery()` method. Therefore, the stock low-voltage checks and MCU logic process the real data before the software correction is applied.

## Reading voltage from the MCU

Only battery packets are processed:

1. `subCmd() == 3`.
2. `paramLen() >= 1`.
3. Read the value from `param(0)`.
4. Interpret the byte as unsigned and convert tenths of a volt:

```kotlin
val rawVoltage = (parameter.toInt() and 0xFF) / 10.0f
```

Example: `0x78` → `120` → **12.0 V**.

Invalid, missing, or incomplete data must not modify the service value.

## Correction algorithm

Parameters:

- Offset range: **−2.0 to +2.0 V**.
- Adjustment step: **0.1 V**.
- Default offset: **0.0 V**.

```text
enabled:  effectiveVoltage = rawBatteryVoltage + voltageOffset
disabled: effectiveVoltage = rawBatteryVoltage
```

The raw value is retained separately as `rawBatteryVoltage`. The offset is always applied to the raw value; it is never added to an already corrected voltage.

When the configuration changes, `mBatteryVoltage` is recalculated from the latest raw value. Disabling the module restores the latest actual voltage.

## Cross-process configuration

The app sends the complete configuration to the `android` process using a package-scoped broadcast:

| Field | Value |
|---|---|
| Action | `com.mt8163.tools.action.VOLTAGE_OFFSET_CHANGED` |
| Target package | `android` |
| Extra | `module_enabled` |
| Extra | `voltage_offset` |

The configuration is published:
- When the app starts.
- When the Voltage module state changes.
- When the offset changes or is reset.

The receiver is registered using the service Context after the `McuService` constructor.

Initial hook configuration is read from a separate `voltage_hook_config` store. On Android 9, `MODE_WORLD_READABLE` is used, with a fallback to `MODE_PRIVATE` if access is denied. The UI offset is stored as a `Float` at `voltage_settings/voltage_offset`. The module enabled state belongs to the shared module-state mechanism.

## Synchronization and constraints

- MCU packet updates and configuration updates are synchronized using the same lock.
- An offset is accepted only if it is finite and within `−2.0..+2.0 V`.
- The hook uses in-memory configuration; no disk I/O is performed while processing MCU packets.
- The original `onBattery()` method is neither replaced nor bypassed.
- Polling, timers, worker threads, executors, and recurring callbacks are not used.

## Key principle

**Only Android's software-reported voltage is corrected.** The MCU continues to send the actual value, and the stock low-voltage protection logic runs before the offset is applied.
