# Protection Firmware Analysis

## Scope and source

This document records technical findings from read-only analysis of the target firmware source.

- Repository: `14vvp93/public_worker`
- Source tree: `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/`
- Target platform: Android 9 (API 28), MT8163 head unit
- Hook API for the project: Legacy Xposed API 82

The functional requirements are defined in [README.md](README.md). This file is for firmware behavior, call flows, candidate interception points, and unresolved technical questions.

Findings are classified as:

- **Confirmed:** directly observed in the inspected source.
- **Candidate:** a possible implementation point that still requires validation.
- **Pending:** source paths or runtime behavior that have not yet been fully traced.

## 1. Recents task loading and identity

### Recents loading flow

**Confirmed** — the inspected loading path is:

1. `RecentsTaskLoadPlan.preloadPlan()` obtains tasks through `ActivityManagerWrapper.getRecentTasks(...)`.
2. `RecentsTaskLoader` loads task data.
3. `RecentsActivity.reloadTaskStack()` obtains the resulting `TaskStack`.
4. `TaskStackView` and `TaskView` represent the tasks in the Recents UI.
5. `RecentsImpl.TaskStackListenerImpl` receives task-stack change notifications through `ActivityManagerWrapper.registerTaskStackListener()`.

The system Recent Tasks list is the source used when Recents is reloaded. Retaining only an old local card or local `TaskStack` entry is insufficient if the corresponding system task has already been removed.

### Task identity

Source: `shared/recents/model/Task.java`

**Confirmed** — `Task.TaskKey` exposes `id`, `baseIntent`, `sourceComponent`, `userId`, and `windowingMode`.

`TaskKey.getPackageName()` first checks the component in `baseIntent`, then falls back to the intent package.

**Candidate** — persistent application settings should be keyed by package name plus Android `userId`. A task ID identifies a task instance and should not be used as the persistent application-level key.

**Pending** — verify nullability and identity behavior for tasks with a null `baseIntent`, null component, or absent package name. A failed identity lookup must not accidentally match another application.

## 2. Swipe-to-dismiss

Source: `recents/views/TaskStackViewTouchHandler.java`

**Confirmed** — `canChildBeDismissed(View v)` checks the swipe-animation map and whether the task exists in the current stack. `getChildAtPosition()` also uses this dismissal eligibility check.

**Candidate** — intercept `canChildBeDismissed(View v)`; return `false` for a task protected by **Keep card in Recents**, and preserve the original result for all other tasks.

**Pending** — inspect manual-drag handling, including `onBeginManualDrag()`, `onBeginDrag()`, and all paths that interact with `mSwipeHelperAnimations`. The hook must not leave a gesture or animation in an inconsistent state.

## 3. Card X dismissal

Sources:

- `recents/views/TaskViewHeader.java`
- `recents/views/TaskView.java`

**Confirmed** — the header click handler invokes `TaskView.dismissTask()`. That method creates a `DismissTaskViewEvent`, configures a dismissal callback, and dispatches the event through EventBus.

**Candidate** — prevent `dismissTask()` from initiating dismissal for a protected task.

**Pending** — enumerate every call site of `dismissTask()`. Do not assume the method is called exclusively by the X button; other callers may require the original behavior.

## 4. Close all

Source: `recents/views/TaskStackView.java`

Handler: `onBusEvent(DismissAllTaskViewsEvent event)`

**Confirmed** — the handler takes a snapshot of tasks from the stack, starts the delete-all animation, calls `mStack.removeAllTasks(true)` in the animation completion callback, dispatches `DeleteTaskDataEvent` for the snapshotted tasks, and records metrics.

Source: `recents/views/TaskStackAnimationHelper.java`

**Confirmed** — delete-all animation has separate grid and non-grid paths:

- `startTaskGridDeleteTaskAnimation()`
- `startTaskStackDeleteAllTasksAnimation()`

Both paths eventually invoke `touchHandler.onChildDismissed()` in animation callbacks. The inspected animation helper does not apply Protection filtering.

**Implementation constraint** — do not merely block `TaskStack.removeAllTasks(true)`. The animations may already have removed cards visually while the model remains unchanged. The method also triggers callbacks that clear task views and ignore-task state.

**Candidate** — filter protected and unprotected tasks before the delete-all flow begins. The visible TaskView list, model updates, animation callbacks, and `DeleteTaskDataEvent` dispatches must remain consistent.

**Pending** — determine the exact safe interception point and callback sequence for both grid and non-grid layouts. The final Close all design has not been established.

## 5. TaskStack model and callbacks

Source: `shared/recents/model/TaskStack.java`

**Confirmed** — `removeTask(...)` removes a task from the stack lists and invokes `onStackTaskRemoved()`; `removeAllTasks(boolean)` clears the stack lists and may invoke `onStackTasksRemoved()`; `setTasks(List<Task>, boolean)` reconciles current and incoming task lists.

Source: `recents/views/TaskStackView.java`

**Confirmed** — `onStackTaskRemoved()` returns the removed card to the ViewPool, updates ignore-task state and layout, and handles the empty-stack state. `onStackTasksRemoved()` returns all task views to the ViewPool, clears ignore-task state, and dispatches `AllTaskViewsDismissedEvent`.

A partially retained stack must not be processed as though the entire stack were removed.

**Pending** — inspect `TaskStack.setTasks(List<Task>, boolean)` in full and determine how protected tasks behave during reload and reconciliation.

## 6. DeleteTaskDataEvent and destructive task removal

Source: `RecentsActivity.java`

Handler: `onBusEvent(DeleteTaskDataEvent event)`

**Confirmed** — the inspected handler calls `RecentsTaskLoader.deleteTaskData(event.task, false)`, calls `ActivityManagerWrapper.getInstance().removeTask(event.task.key.id)`, and calls `ActivityManager.forceStopPackage(...)` for the task package.

This event is not a cache-only operation. It can remove the system Recent Task and force-stop the application.

**Protection constraint** — a protected task must not reach this destructive handler as part of a protected Recents dismissal or Close all operation.

**Null-safety constraint** — the inspected code obtains the package through `event.task.key.getComponent().getPackageName()`. Verify component availability and handle tasks whose component or intent data is absent.

**Pending** — trace every producer and consumer of `DeleteTaskDataEvent`, not only the currently inspected Close all path.

### System task removal

Source: `shared/system/ActivityManagerWrapper.java`

**Confirmed** — `removeTask(final int taskId)` calls `ActivityManager.getService().removeTask(taskId)` through `BackgroundExecutor`.

This system operation is distinct from removing a task from the in-memory `TaskStack`. Protection must account for both layers.

## 7. MTK Memory Cleaner

Source: `qs/tiles/MemoryCleanerTile.java`

**Confirmed** — the inspected cleaner iterates its task list and, for each task, calls `RecentsTaskLoader.deleteTaskData(task, false)`, calls `ActivityManagerWrapper.getInstance().removeTask(task.key.id)`, and calls `ActivityManager.forceStopPackage(...)`.

This is a separate deletion path from the Recents X and Close all flows. Protecting only the Recents UI does not protect tasks from Memory Cleaner.

**Candidate** — filter or skip protected tasks in the cleaner's task-processing path before any destructive operation is performed. Preserve the original processing for all unprotected tasks.

**Pending** — inspect the complete cleaner method to identify how it obtains and orders its task list, whether it has other cleanup branches, and whether additional Memory Cleaner entry points exist.

## 8. Implementation constraints

- Keep the TaskStack model, TaskView animations, callbacks, and system Recent Tasks list consistent.
- Preserve original firmware behavior for unprotected tasks.
- Use package name and Android user ID for application-level matching.
- Handle missing intent, component, and package data safely.
- Do not confuse removing a Recents task with terminating an application process.
- Do not bypass TaskStack callbacks without reproducing their required UI cleanup.
- Do not interfere with Android's normal process lifecycle or system memory management.
- Do not assume all dismissal paths share one method or event.

## 9. Outstanding research

1. Enumerate all call sites of `TaskView.dismissTask()`.
2. Trace all producers and consumers of `DeleteTaskDataEvent`.
3. Inspect `TaskStack.setTasks(List<Task>, boolean)` in full and determine how protected tasks behave during reload and reconciliation.
4. Trace the complete grid and non-grid Close all callback sequence, including task-view filtering and trigger completion.
5. Inspect the complete Memory Cleaner method and search for other cleanup entry points.
6. Trace relevant `TaskStackListener` callbacks and Recents reload behavior after system task changes.
7. Verify nullability and package/user identity for unusual tasks, including tasks without a resolved component.
8. Validate the behavior on the target MT8163 head unit running Android 9.

## 10. Research status

### Confirmed from inspected source

- Swipe dismissal eligibility is checked by `TaskStackViewTouchHandler.canChildBeDismissed()`.
- The card header X action calls `TaskView.dismissTask()`.
- Close all uses a separate bulk-dismiss path with grid and non-grid animations.
- `DeleteTaskDataEvent` deletes task data, removes the system task, and force-stops the package.
- Memory Cleaner independently deletes task data, removes system tasks, and force-stops packages.
- Recents reloads tasks from the system Recent Tasks list.
- TaskStack removal callbacks perform important UI cleanup and cannot be bypassed casually.

### Not yet completed

- No Protection hooks have been implemented.
- No final Close all interception design has been approved.
- The outstanding research listed above remains.
- Firmware behavior has not yet been validated with the Protection implementation on the target head unit.
