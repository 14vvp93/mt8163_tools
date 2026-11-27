# Voltage Hook

## Target

- Hook class: `com.mt8163.tools.xposed.hooks.voltage.VoltageHook`.
- Target package and LSPosed scope: `android`.
- Target class: `android.carsource.McuService`.
- Constructor: `McuService(Context)`.
- Method: `onBattery(McuPacket)`.
- Method descriptor: `(Landroid/carsource/McuService$McuPacket;)V`.
- Hook timing: after-hook.
- Updated field: `McuService.mBatteryVoltage`.

The module UI and settings are documented in [Voltage Module](../../../modules/voltage/README.md).

## MCU value

The MCU command handler dispatches battery command `55` to `onBattery(McuPacket)`. The method processes packets with `subCmd() == 3` and reads the first parameter as an unsigned byte in tenths of a volt:

~~~text
rawVoltage = (param(0) & 0xFF) / 10.0
~~~

The original method runs before the after-hook. Its low-voltage and reboot logic is not changed by Voltage.

## Correction

For a valid nonzero battery value, the hook stores the raw voltage and applies:

~~~text
reportedVoltage = rawVoltage + voltage_offset
~~~

The offset is always applied to the latest raw MCU value, never to a previously corrected value. No output-voltage clamp is applied; the settings layer restricts the finite offset to `-2.0..+2.0 V`.

A zero-voltage packet clears the cached-raw-value flag and is not overwritten by the hook. The original MCU method remains responsible for the field value for that packet.

When the module is disabled, the hook restores the latest cached raw voltage when one is available. A zero offset reports the raw value without applying a correction.

## Installation and cleanup

The constructor and battery-method hooks are installed as one unit. If either hook cannot be installed, any partially installed hook is removed and the installation is rolled back.

The constructor hook captures the `McuService` instance and loads the initial configuration. The battery hook processes valid MCU battery packets only while the module is enabled.

Disabling Voltage restores the latest valid raw voltage, removes both hooks, and clears transient service and raw-value references.

## Configuration

The hook reads initial settings through Legacy `XSharedPreferences`:

- Preferences file: `voltage_hook_config`.
- Keys: `module_enabled` and `voltage_offset`.

Subsequent changes are delivered by the shared SystemServer `SystemConfigReceiver`:

- Action: `com.mt8163.tools.action.VOLTAGE_CONFIG_CHANGED`.
- Target package: `android`.
- Permission: `com.mt8163.tools.permission.UPDATE_VOLTAGE_CONFIG`.
- Fields: `module_enabled` (boolean), `voltage_offset` (finite float).

The receiver is shared infrastructure; VoltageHook does not register its own dynamic receiver. Invalid offsets are rejected without replacing the last valid configuration.

## Diagnostics

Voltage reports hook installation, configuration, packet reception, voltage application, restoration, and failure events through Core's `EventLogger`. The Events module is only the consumer; the hook does not depend on Events storage or UI.

## Device validation

Hook resolution, configuration delivery, raw-value restoration, and the resulting reported voltage must be verified on the target head unit. Firmware decoding notes are based on the decompiled source in [public_worker/artifacts/CarServises](https://github.com/14vvp93/public_worker/tree/main/artifacts/CarServises).
