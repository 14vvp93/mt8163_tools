# Dynamic Module Lifecycle

## Purpose

Modules must be enabled and disabled at runtime without rebooting the head unit. Disabling a module must stop its feature hooks and operations while preserving its saved configuration.

This document describes the agreed design. It does not describe an implemented feature; code changes and device validation are still required.

## Runtime model

Each module owns its feature hooks and runtime resources. The module lifecycle is managed independently in each process where that module runs.

1. **Enable**
   - Read the latest persisted configuration.
   - Install the module's hooks.
   - Start the operations and listeners required by the module.
   - Mark the module enabled only after successful installation.
2. **Disable**
   - Stop accepting new module work.
   - Cancel or finish active module operations safely.
   - Unhook every hook installed by the module.
   - Restore any system state changed by the module.
   - Release temporary resources and references.
   - Preserve saved settings and persistent user data.
3. **Re-enable**
   - Read the latest configuration again.
   - Recreate required resources and install hooks.
   - Avoid duplicate hooks or duplicate listeners.

Repeated enable/disable requests must be safe and idempotent.

## Hook ownership and cleanup

- Each module stores every Unhook handle returned by its feature-hook installations.
- Hook installations that return multiple handles must retain every handle.
- Hook installations must be rolled back if a later installation step fails.
- Cleanup must be safe after partial installation or when a resource was never created.
- Shared support hooks must be identified separately from feature hooks and must not perform module feature work.

The PackageHook contract is expected to expose an uninstall() operation in addition to its existing package support and installation methods. HookRegistry will coordinate module lifecycle transitions.

## Lifecycle safety

Module transitions must be synchronized so callbacks cannot continue changing system state after disable cleanup has completed.

The implementation must:
- Prevent duplicate installation.
- Prevent new work from starting during shutdown.
- Handle partial installation failures by rolling back successful steps.
- Cancel asynchronous work before releasing resources it may use.
- Clear transient references to avoid leaks.
- Keep module configuration separate from runtime enabled state.

A failed enable must leave the module disabled and clean up any partial installation. A repeated disable must be safe when the module is already disabled.

## Process and configuration coordination

The modules run in different Android processes:
- **Voltage and Media:** android / system_server.
- **Protection:** com.android.systemui.

Each process has its own static runtime state. Updating a setting in the app process alone is therefore insufficient.

The configuration update flow must:
1. Persist the new configuration.
2. Send the appropriate protected configuration broadcast.
3. Let the receiver in each target process reload the persisted configuration.
4. Apply the module transition in that process.

Shared infrastructure must remain available regardless of individual module states. SystemBootstrap hooks and the shared SystemConfigReceiver are infrastructure, not feature hooks, and must not be removed when one module is disabled.

## Module-specific behavior

### Voltage

When disabled:
- Stop processing new voltage callbacks.
- Unhook all Voltage feature hooks.
- Restore the latest actual raw MCU voltage received by the module to the MCU service field.
- Do not write a guessed value if no raw voltage has been received or the service is unavailable.
- Ensure no in-flight callback can reapply the offset after restoration.

The saved offset and other configuration remain available for the next enable.

### Media

When disabled:
- Stop accepting new Media operations.
- Cancel any active restoration or pending module operation.
- Close the BluetoothMusicClient.
- Clear transient operation state.
- Unhook the ACC receiver feature hook.
- Preserve the saved snapshot and module configuration for re-enable.

The WindowManagerService constructor hook is a lightweight support hook. It only captures the long-lived service instance needed for foreground-source detection; it remains installed across Media toggles, and its service reference is retained so re-enabling Media does not require restarting system_server. It does not perform snapshot or playback work while Media is disabled.

Media uses one shared last-snapshot record. The saved state is displayed only in the matching section (Media Player or Bluetooth); the other section displays Unknown when the snapshot does not match or contains no data.

### Protection

When disabled:
- Stop applying Recents and Memory Cleaner filtering.
- Unhook all Protection feature hooks.
- Clear temporary filtering state, including thread-local state.
- Unregister the module-owned receiver where applicable.
- Refresh the Recents task list so previously hidden applications become visible without restarting SystemUI.
- Preserve Protection settings for re-enable.

A native Recents reload path has been identified in the target ROM source. Its behavior must be verified on the actual head unit, including when Recents is open and when multi-window mode is active. Unhooking alone is not sufficient because filtering may already have modified in-memory task data.

## Performance expectations

No persistent performance increase is expected from this design.
- An enabled module has approximately its normal hook and operation overhead.
- A disabled module no longer runs its feature callbacks or operations.
- Enabling and disabling cause short-lived work for hook installation/removal and cleanup.
- Shared infrastructure remains active because it coordinates configuration and system lifecycle events.

The implementation should remain event-driven. It must not introduce polling loops or periodic timers.

## Validation requirements

Before considering the feature complete, verify on the target device:
- Every module can be enabled and disabled without rebooting the head unit.
- Repeated toggles do not create duplicate hooks, receivers, or clients.
- Partial installation failures clean up all successfully installed resources.
- Voltage restores the latest real MCU value and does not reapply the offset after disable.
- Media stops active work, closes its Bluetooth client, and retains its saved snapshot.
- Protection restores hidden Recents entries without restarting SystemUI.
- Configuration changes reach the correct processes.
- Disabled modules do not continue performing feature work.

## Current status

Initial runtime lifecycle support is implemented for Voltage and Media. It has not yet been built or validated on the target device.

Protection lifecycle support remains pending. Protection's Recents refresh and all module transitions require on-device validation before the feature is considered complete.
