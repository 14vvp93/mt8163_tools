# Voltage Module

## Module Role

Voltage is a feature module of MT8163 Tools. It provides one user-controlled adjustment to the battery voltage value exposed by the Android MCU service.

The module has two project layers:

- **Module UI and settings:** `com.mt8163.tools.modules.voltage`
- **LSPosed hook:** `com.mt8163.tools.xposed.hooks.voltage`

The UI belongs to the MT8163 Tools application. The hook runs in the Android `system_server` process. These layers communicate through the module configuration contract; the UI does not call hook classes directly.

Voltage is disabled by default.

## UI Design

The Voltage screen is part of the existing module UI. Its controls appear in this order:

1. **Module Enabled** — the main switch controlling voltage correction.
2. **Voltage Offset** — the correction value, from `−2.0 V` to `+2.0 V`, in `0.1 V` steps. The default is `0.0 V`.
3. **Reset** — sets the offset to `0.0 V`. It does not change the Module Enabled state.

The offset remains saved when the module is disabled. Disabling the module bypasses the correction; it does not erase the user's offset.

### UI behavior

- When the screen opens, it displays the saved module state and offset.
- Changing **Module Enabled** saves the new state and publishes the complete Voltage configuration.
- Changing **Voltage Offset** saves the new value and publishes the complete Voltage configuration.
- Pressing **Reset** saves `0.0 V` as the offset and publishes the complete configuration.
- The UI reflects the saved values; it does not display a fabricated or independently calculated battery reading.

The module's enabled state uses the project's shared module-state mechanism. The offset is stored in the Voltage module's preferences.

## Runtime Architecture

```text
MT8163 Tools application
│
├── ModuleRegistry
│   └── VoltageModule
│       └── Voltage UI
│           ├── Module Enabled
│           ├── Voltage Offset
│           └── Reset
│
├── Persist module state and offset
│
└── Publish complete Voltage configuration
    │
    └── Package-scoped broadcast
        │
        ▼
Android process (system_server)
│
├── Voltage configuration receiver
│   ├── Validate configuration
│   └── Update in-memory configuration
│
└── McuService
    └── onBattery(McuPacket)
        └── Original method completes
            └── Voltage after-hook
                ├── Read battery packet value
                ├── Retain raw voltage
                ├── Apply enabled state and offset
                └── Update mBatteryVoltage
```

## MCU Integration

The hook target is the Android MCU service:

| Property | Target |
|---|---|
| Process / package | `android` (`system_server`) |
| Class | `android.carsource.McuService` |
| Constructor | `McuService(Context)` |
| Method | `onBattery(McuPacket)` |
| Method descriptor | `(Landroid/carsource/McuService$McuPacket;)V` |
| Hook timing | LSPosed after-hook |
| Updated field | `McuService.mBatteryVoltage` |
| LSPosed scope | `android` |

The original `onBattery()` method runs unchanged and completes before Voltage applies the correction. Consequently, the original MCU handling and low-voltage protection logic process the unmodified MCU data.

Only battery packets are eligible for correction:

- `subCmd() == 3`
- `paramLen() >= 1`
- The voltage byte is read from `param(0)`.

The byte is unsigned and represents tenths of a volt:

```text
rawVoltage = (parameter & 0xFF) / 10.0
```

For example, `0x78` represents `12.0 V`. Missing or incomplete packet data must not change the service voltage.

## Voltage Correction

The hook retains the latest decoded MCU value separately as `rawBatteryVoltage`.

```text
if Module Enabled:
    effectiveVoltage = rawBatteryVoltage + voltageOffset
else:
    effectiveVoltage = rawBatteryVoltage
```

The offset is always calculated from the retained raw value. It must never be added to a previously corrected value.

When a new valid battery packet arrives, the hook updates the raw value and calculates the effective value. When the configuration changes, the hook recalculates `mBatteryVoltage` from the latest retained raw value, if one is available.

Disabling the module restores the latest raw voltage in the service field. Resetting the offset sets the correction to zero without changing the module enabled state.

## Configuration Delivery

The application publishes the complete configuration to the `android` process using a package-scoped broadcast.

Configuration fields:

| Field | Meaning |
|---|---|
| `module_enabled` | Whether Voltage correction is active |
| `voltage_offset` | Offset in volts |

The broadcast is published:

- When the application starts, after saved settings are loaded.
- Whenever the Voltage module enabled state changes.
- Whenever the offset changes or is reset.

The receiver is registered with the `McuService` context after the service constructor. It validates incoming values before updating the in-memory configuration.

The initial configuration must also be available to the target process through a process-safe settings store. Runtime broadcasts keep the in-memory configuration synchronized after startup. The exact storage and broadcast identifiers must be shared by the app-side publisher and the hook-side receiver; they must not be independently redefined.

## Synchronization and Runtime Constraints

- Packet processing and configuration updates use the same lock.
- The offset must be finite and within `−2.0..+2.0 V`.
- The hook hot path uses in-memory configuration and performs no disk I/O.
- Invalid configuration or invalid packet data must not corrupt the last valid service value.
- The original MCU method is not replaced, bypassed, or modified.
- No polling, timers, worker threads, executors, or recurring callbacks are used.

## Project Boundaries

- The module implementation belongs under `app/src/main/java/com/mt8163/tools/modules/voltage/`.
- Its UI resources belong in the existing application resource structure; the current module contract references `R.layout.module_voltage`.
- The hook implementation belongs under `app/src/main/java/com/mt8163/tools/xposed/hooks/voltage/`.
- Hook registration follows the project's `PackageHook` and `HookRegistry` architecture.
- UI code must not depend on Xposed classes or hook implementation classes.
- All documentation remains in the repository-root `docs/` directory and is written in English.

## Scope

Voltage changes only the software-reported value stored in `McuService.mBatteryVoltage`.

It does not change the physical electrical voltage, MCU measurements, charging behavior, hardware configuration, or the original low-voltage protection logic.
