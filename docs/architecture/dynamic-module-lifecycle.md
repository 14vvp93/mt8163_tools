# Module Lifecycle

## Runtime model

Module settings are stored by the application. Runtime hooks execute in their target process and receive configuration updates through protected broadcasts.

The processes are independent:

| Module | Target process |
|---|---|
| Media | `android` |
| Voltage | `android` |
| Protection | `com.android.systemui` main process |
| Events | Application process |
| Autorun | No runtime hook |

A setting change in the application process does not directly change static state in another process. The target process must receive and apply the configuration update.

## Xposed registration

`XposedEntry` forwards package-load callbacks to `HookRegistry`.

- `android` receives Media and Voltage hooks.
- The main `com.android.systemui` process receives the Protection hook.
- `SystemBootstrap` and `SystemConfigReceiver` provide shared SystemServer initialization and configuration delivery.

`HookRegistry.setModuleEnabled()` delegates enable and disable operations to the corresponding `PackageHook`.

## Enable and disable behavior

### Media

- The WindowManagerService constructor hook is support infrastructure. It captures the service instance used for foreground-source detection and remains installed across Media toggles.
- The ACC receiver hook is the Media feature hook. It is installed when enabled and removed when disabled.
- Disabling cancels active restoration, closes the Bluetooth client, and clears transient operation state.
- The saved Snapshot and settings are preserved.

### Voltage

- The MCU service constructor and battery-packet hooks are feature hooks.
- Disabling restores the latest valid raw MCU voltage to `mBatteryVoltage`, then removes both hooks and clears transient runtime references.
- The saved offset remains available for the next enable.

### Protection

- SystemUIApplication initialization and Recents activity tracking hooks are support hooks. They remain available for configuration delivery and Recents lifecycle tracking.
- Recents dismissal/filtering and Memory Cleaner hooks are feature hooks.
- Disabling clears the active task matcher and removes the feature hooks. Support hooks remain installed so a later configuration update can enable Protection again.
- If the Recents stack was modified, the implementation requests a native stack refresh after feature hooks are removed.

### Events

Events is not an Xposed hook. Disabling the module stops accepting new events and completes its storage shutdown path while preserving existing records.

### Autorun

Autorun currently has no runtime implementation or Xposed hook.

## Resource ownership

Each hook implementation retains its Xposed `Unhook` handles and removes the feature hooks it owns. Shared support hooks are kept separate from feature hooks.

Cleanup must tolerate partial installation and repeated disable requests. Asynchronous operations must be cancelled or invalidated before resources they use are released.

## Execution constraints

- Runtime work is event-driven.
- No polling loops or periodic timers.
- Configuration updates are delivered through broadcasts.
- No module may depend on Events storage or UI.
- Static state must not be treated as shared across Android processes.

## Validation status

Source implementation is present for Media, Voltage, and Protection lifecycle handling. Actual hook resolution, vendor behavior, configuration delivery, and UI behavior still require validation on the target head unit.
