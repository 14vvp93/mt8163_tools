# Protection Module

## Purpose

Protection controls how selected applications are represented and retained in the Android 9 Recents interface. It prevents selected tasks from being dismissed through Recents actions and can independently exclude selected applications from the Recents UI or the MTK SystemUI Memory Cleaner.

Target platform: Android 9 (API 28), MT8163 head unit.

This document records the agreed functional behavior and the findings from read-only analysis of the target firmware in `14vvp93/public_worker`, under `artifacts/MtkSystemUI`. It is implementation documentation, not a description of completed hooks.

## Per-application settings

Each selected application has three settings.

### 1. Keep card in Recents

- **Enabled:** protect the application's Recents task from Swipe-to-dismiss, the card's X button, and **Close all**.
- **Disabled:** these Recents dismissal actions use the firmware's normal behavior.
- The setting protects the Recents task/card. It must not prevent Android from managing the application's process or lifecycle.

### 2. Hide from Recents

- **Enabled:** the application's task must not be displayed in Recents.
- **Disabled:** the application may be displayed according to the normal Recents behavior.
- Enabling this setting automatically disables **Keep card in Recents**.
- If **Keep card in Recents** is enabled, enabling this setting turns the first setting off.
- Hiding a card must not, by itself, terminate the application's process.

### 3. Protect from Memory Cleaner

- **Enabled:** the MTK SystemUI Memory Cleaner must skip tasks belonging to this application.
- **Disabled:** Memory Cleaner uses its normal behavior for this application.
- This setting is independent of the two Recents settings.
- It applies only to the inspected Memory Cleaner operation. It does not disable Android's system memory management or prevent the system from reclaiming resources.

All three settings are disabled by default.

## Agreed behavior

| Operation | Keep card in Recents | Hide from Recents | Protect from Memory Cleaner |
|---|---|---|---|
| Swipe-to-dismiss | Block dismissal | Not applicable; card is hidden | No effect |
| Card X button | Block dismissal | Not applicable; card is hidden | No effect |
| Close all | Exclude protected task | Not applicable; card is hidden | No effect |
| Display in Recents | Keep card visible | Do not display card | No effect |
| MTK Memory Cleaner | No effect | No effect | Skip this application's task |

Applications not selected in Protection retain normal firmware behavior.

## Firmware architecture findings

The inspected Recents implementation maintains both a SystemUI task model and a system-provided Recent Tasks list. A card-only UI change is therefore insufficient to guarantee retention.

### Recents data flow

The inspected loading path is:

1. `RecentsTaskLoadPlan.preloadPlan()` obtains tasks through `ActivityManagerWrapper.getRecentTasks(...)`.
2. `RecentsTaskLoader` loads task data.
3. `RecentsActivity.reloadTaskStack()` obtains the resulting `TaskStack`.
4. `TaskStackView` and `TaskView` represent the tasks in the Recents UI.
5. `RecentsImpl.TaskStackListenerImpl` receives task-stack change notifications through `ActivityManagerWrapper.registerTaskStackListener()`.

The system Recent Tasks list is the source used when Recents is reloaded. If a task is removed from the system list, retaining an old local card or local `TaskStack` entry alone will not restore it on the next reload.

## Inspected firmware code paths

The following paths are from the JADX source tree:

`artifacts/MtkSystemUI/jadx/sources/com/android/systemui/`

### Swipe-to-dismiss

File: `recents/views/TaskStackViewTouchHandler.java`

Method: `canChildBeDismissed(View v)`

The method checks the swipe-animation map and whether the task exists in the current stack. `getChildAtPosition()` also uses this dismissal eligibility check.

**Candidate hook:** intercept the method and return `false` for a task protected by **Keep card in Recents**; preserve the original result for all other tasks.

**Additional verification required:** inspect manual-drag handling, including `onBeginManualDrag()`, `onBeginDrag()`, and all paths that interact with `mSwipeHelperAnimations`. The hook must not leave a gesture or animation in an inconsistent state.

### Card X button

Files:

- `recents/views/TaskViewHeader.java`
- `recents/views/TaskView.java`

The header click handler invokes `TaskView.dismissTask()`. That method creates a `DismissTaskViewEvent`, configures a dismissal callback, and dispatches the event through EventBus.

**Candidate hook:** prevent `dismissTask()` from initiating dismissal for a protected task.

**Additional verification required:** enumerate every call site of `dismissTask()`. Do not assume the method is called exclusively by the X button; other callers may require the original behavior.

### Close all

File: `recents/views/TaskStackView.java`

Handler: `onBusEvent(DismissAllTaskViewsEvent event)`

The handler snapshots the tasks from the stack, starts the delete-all animation, and in its completion callback calls `mStack.removeAllTasks(true)`. It then dispatches `DeleteTaskDataEvent` for the snapshotted tasks and records metrics.

File: `recents/views/TaskStackAnimationHelper.java`

The delete-all animation has separate grid and non-grid paths:

- `startTaskGridDeleteTaskAnimation()`
- `startTaskStackDeleteAllTasksAnimation()`

Both paths eventually invoke `touchHandler.onChildDismissed()` in animation callbacks. The inspected animation helper does not apply Protection filtering.

**Implementation constraint:** do not merely block `TaskStack.removeAllTasks(true)`. The animations may already have removed cards visually, while the model remains unchanged. The method also triggers callbacks that clear task views and ignore-task state.

**Candidate approach:** filter protected and unprotected tasks before the delete-all flow begins, and keep the visible TaskView list, model updates, animation callbacks, and `DeleteTaskDataEvent` dispatches consistent. The exact safe interception point and callback sequence are not yet finalized.

### TaskStack model and callbacks

File: `shared/recents/model/TaskStack.java`

Inspected operations:

- `removeTask(...)` removes a task from the stack lists and invokes `onStackTaskRemoved()`.
- `removeAllTasks(boolean)` clears the stack lists and may invoke `onStackTasksRemoved()`.
- `setTasks(List<Task>, boolean)` reconciles the current and incoming task lists and must be examined in full before designing retention across reloads.

File: `recents/views/TaskStackView.java`

The inspected callbacks perform UI and model maintenance:

- `onStackTaskRemoved()` returns the removed card to the ViewPool, updates ignore-task state and layout, and handles the empty-stack state.
- `onStackTasksRemoved()` returns all task views to the ViewPool, clears ignore-task state, and dispatches `AllTaskViewsDismissedEvent`.

A partial protected stack must not be processed as though the entire stack were removed.

### DeleteTaskDataEvent

File: `RecentsActivity.java`

Handler: `onBusEvent(DeleteTaskDataEvent event)`

The inspected handler performs all of the following:

1. Calls `RecentsTaskLoader.deleteTaskData(event.task, false)`.
2. Calls `ActivityManagerWrapper.getInstance().removeTask(event.task.key.id)`.
3. Calls `ActivityManager.forceStopPackage(...)` for the task package.

This event is therefore not a cache-only operation. It can remove the system Recent Task and force-stop the application.

**Protection requirement:** a protected task must not reach this destructive handler as part of a protected Recents dismissal or Close all operation.

**Null-safety requirement:** the inspected code obtains the package through `event.task.key.getComponent().getPackageName()`. The implementation must verify component availability and handle tasks whose component or intent data is absent.

### Task identity

File: `shared/recents/model/Task.java`

`Task.TaskKey` exposes task identity and intent-related fields, including:

- `id`
- `baseIntent`
- `sourceComponent`
- `userId`
- `windowingMode`

`TaskKey.getPackageName()` first checks the component in `baseIntent`, then falls back to the intent package.

**Recommended configuration identity:** package name together with Android `userId`. Do not use task ID as the persistent application-level key because it identifies a task instance, not an application.

**Null-safety requirement:** handle a null `baseIntent`, null component, and absent package name without crashing or accidentally matching another application.

### System task removal

File: `shared/system/ActivityManagerWrapper.java`

The inspected `removeTask(final int taskId)` calls `ActivityManager.getService().removeTask(taskId)` through `BackgroundExecutor`.

This system operation is distinct from removing a task from the in-memory `TaskStack`. Protection must account for both layers.

### Memory Cleaner

File: `qs/tiles/MemoryCleanerTile.java`

The inspected cleaner iterates its task list and, for each task, performs:

1. `RecentsTaskLoader.deleteTaskData(task, false)`.
2. `ActivityManagerWrapper.getInstance().removeTask(task.key.id)`.
3. `ActivityManager.forceStopPackage(...)`.

This is a separate deletion path from the Recents X and Close all flows. Protecting only the Recents UI does not protect tasks from Memory Cleaner.

**Candidate hook:** filter or skip protected tasks in the cleaner's task-processing path before any destructive operation is performed. Preserve the original processing for all unprotected tasks.

**Additional verification required:** inspect the complete method to identify how the cleaner obtains and orders its task list, whether it has other cleanup branches, and whether there are additional cleaner entry points.

## Implementation constraints

- Keep Protection isolated as an MT8163 Tools module.
- Use the target project's agreed Legacy Xposed API 82.
- Match tasks using package name and Android user ID, with null-safe extraction.
- Preserve original firmware behavior for unprotected tasks.
- Do not confuse removing a Recents task with terminating an application process.
- Keep the TaskStack model, TaskView animations, callbacks, and system Recent Tasks list consistent.
- Do not block Android's normal process and memory management.
- Do not assume that all dismissal paths share one method or event.

## Remaining firmware research

The following work is required before the hook design can be considered complete:

1. Enumerate all call sites of `TaskView.dismissTask()`.
2. Trace all producers and consumers of `DeleteTaskDataEvent`.
3. Inspect `TaskStack.setTasks(List<Task>, boolean)` in full and determine how protected tasks behave during reload and reconciliation.
4. Trace the complete grid and non-grid Close all callback sequence, including task-view filtering and trigger completion.
5. Inspect the complete Memory Cleaner method and search for other cleanup entry points.
6. Trace the relevant `TaskStackListener` callbacks and Recents reload behavior after system task changes.
7. Verify nullability and package/user identity for unusual tasks, including tasks without a resolved component.
8. Validate the behavior on the target MT8163 head unit running Android 9.

## Current status

### Confirmed from inspected firmware source

- Swipe dismissal eligibility is checked by `TaskStackViewTouchHandler.canChildBeDismissed()`.
- The card header X action calls `TaskView.dismissTask()`.
- Close all uses a separate bulk-dismiss path with grid and non-grid animations.
- `DeleteTaskDataEvent` deletes task data, removes the system task, and force-stops the package.
- Memory Cleaner independently deletes task data, removes system tasks, and force-stops packages.
- Recents reloads tasks from the system Recent Tasks list.
- TaskStack removal callbacks perform important UI cleanup and cannot be bypassed casually.

### Agreed functional behavior

- **Keep card in Recents** protects from Swipe, X, and Close all.
- **Hide from Recents** hides the card and automatically disables **Keep card in Recents**.
- **Protect from Memory Cleaner** is an independent, opt-in setting.
- All settings are disabled by default.
- Protection must not itself terminate an application's process.

### Not yet completed

- No Protection hooks have been implemented.
- No final Close all interception design has been approved.
- The remaining research listed above is still required.
- Firmware behavior has not yet been validated with the Protection implementation on the target head unit.
