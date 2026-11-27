# Protection Hook

## Target

- Hook class: `com.mt8163.tools.xposed.hooks.protection.ProtectionHook`
- Target package: `com.android.systemui`
- Target process: main `com.android.systemui` process
- LSPosed scope: `com.android.systemui`
- Module ID: `protection`

The hook is registered through `HookRegistry`. It does not run in `system_server`.

## Support hooks

Support hooks remain installed while Protection is disabled:

- `SystemUIApplication.onCreate()` — registers the process configuration receiver after SystemUI application initialization.
- Recents activity lifecycle tracking — retains a weak reference to the current RecentsActivity for stack refresh after filtering changes.

These hooks provide lifecycle/configuration support. They do not apply the Protection feature filters while the module is disabled.

## Feature hooks

Feature hooks are installed only while Protection is enabled.

| Behavior | SystemUI interception point |
|---|---|
| Keep against swipe dismissal | `TaskStackViewTouchHandler.canChildBeDismissed(View)` |
| X-button exception | Dismiss-button handling in `TaskViewHeader.onClick(View)` |
| Keep against keyboard dismissal | `TaskStackView.onBusEvent(DismissFocusedTaskViewEvent)` |
| Keep against Close all | `TaskStackView.onBusEvent(DismissAllTaskViewsEvent)` |
| Hide from initial Recents stack | `RecentsView.onReload(TaskStack, boolean)` |
| Hide during stack refresh | `MultiWindowStateChangedEvent(TaskStack)` |
| Memory Cleaner exclusion | `MemoryCleanerTile.clearRecentsTask()`, `deleteTaskData()`, `removeTask()`, and `forceStopPackage()` guards |

The implementation preserves normal firmware behavior for applications not selected in Protection. Keep is defined by application membership in the Protection list; there is no separate Keep setting.

The X-button exception, display-only Hide, and Memory Cleaner protection are independent per-application settings. Their user-visible behavior is documented in the [Protection module](../../../modules/protection/README.md).

## Configuration

The hook loads the initial settings from the application's `protection_hook_config` preferences and registers for subsequent configuration updates in the SystemUI process.

Configuration is matched by validated package name. Task IDs are transient and are not used as persistent application identifiers.

## Disable behavior

Disabling Protection clears the active task matcher and removes the feature hooks. Support hooks remain available so a later configuration update can re-enable the feature without restarting SystemUI.

If the feature modified the in-memory Recents stack, the implementation requests a native stack refresh after the filtering hooks have been removed.

## Validation

Hook installation and the target method signatures depend on the head unit's SystemUI build. Verify hook resolution, each dismissal path, Hide refresh behavior, Memory Cleaner exclusions, and stack restoration on the actual device.

Firmware source findings and the broader scope boundary are recorded in [Protection Firmware Analysis](../../../modules/protection/ANALYSIS.md).
