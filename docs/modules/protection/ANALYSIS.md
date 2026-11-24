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

## 11. Recents task loading and cache lifecycle

Sources:

- `shared/recents/model/RecentsTaskLoadPlan.java`
- `shared/recents/model/RecentsTaskLoader.java`
- `shared/system/ActivityManagerWrapper.java`

**Confirmed** — `RecentsTaskLoadPlan.preloadPlan()` obtains the current user's system Recent Tasks through:

```java
ActivityManagerWrapper.getInstance().getRecentTasks(
    ActivityManager.getMaxRecentTasksStatic(),
    currentUserId
);
```

The returned list is reversed before conversion into Recents `Task` objects. Each task key is constructed from the persistent task ID, windowing mode, base intent, source component, user ID, and last-active time.

**Confirmed** — `ActivityManagerWrapper.getRecentTasks()` calls the ActivityManager service with flags value `2` and returns an empty list if a `RemoteException` occurs.

**Confirmed** — `RecentsTaskLoadPlan` builds a new `TaskStack` from the tasks it accepts. It skips entries whose activity information cannot be resolved, entries whose source component is already represented in the current load plan, configured hidden packages, and the current dock activity.

**Implementation constraint** — hiding a card from the Recents UI must not remove the corresponding system task. A filtered display list and the system Recent Tasks list are different layers.

### TaskKey identity

Source: `shared/recents/model/Task.java`

**Confirmed** — `TaskKey.equals()` and `hashCode()` use exactly:

- task ID;
- windowing mode;
- Android user ID.

The base intent, source component, and last-active time are not part of equality.

**Implementation constraint** — use the package name and user ID for Protection's persistent application settings. Use the task ID only when an operation specifically targets a task instance. Do not treat `TaskKey.equals()` as application identity.

**Null-safety finding** — `TaskKey.getPackageName()` checks whether the base intent has a component, then falls back to the intent package. It still assumes that `baseIntent` itself is non-null. The inspected Recents loader also dereferences `t.realActivity` in some paths. Protection matching must therefore validate the available task identity before reading it and fail closed for destructive-operation interception.

## 12. Destructive cleanup paths

### Recents dismissal event

Sources:

- `recents/views/TaskStackView.java`
- `recents/RecentsActivity.java`
- `shared/recents/model/RecentsTaskLoader.java`
- `shared/system/ActivityManagerWrapper.java`

**Confirmed** — the individual card dismissal path removes the task from the in-memory `TaskStack`, then dispatches `DeleteTaskDataEvent`.

**Confirmed** — `RecentsActivity.onBusEvent(DeleteTaskDataEvent)` performs all of the following:

1. Calls `RecentsTaskLoader.deleteTaskData(task, false)`.
2. Requests system task removal through `ActivityManagerWrapper.removeTask(taskId)`.
3. Calls `ActivityManager.forceStopPackage(packageName)`.

**Confirmed** — `RecentsTaskLoader.deleteTaskData()` removes the task from the background load queue and removes its icon, activity-label, and content-description cache entries. It does not itself remove the system Recent Task or force-stop the package.

**Implementation constraint** — blocking only the system `removeTask()` call is insufficient: task data may already have been removed from Recents caches and the in-memory stack. The complete dismissal path must be prevented for protected tasks before destructive state changes begin.

### MTK Memory Cleaner

Source: `qs/tiles/MemoryCleanerTile.java`

**Confirmed** — `MemoryCleanerTile.clearRecentsTask()` creates a fresh load plan, preloads and loads the current user's Recents tasks, then iterates the resulting stack. For every task it directly calls `deleteTaskData()`, `removeTask(taskId)`, and `forceStopPackage(packageName)`.

**Confirmed** — this cleaner path does not dispatch `DeleteTaskDataEvent`. Intercepting only that event will not protect tasks from Memory Cleaner.

**Candidate** — apply the Memory Cleaner protection check before the first destructive call for each task. The check must use package name and user ID and preserve the original sequence for unprotected tasks.

## 13. Revised implementation constraints

- The system Recent Tasks list is authoritative for Recents reload; retaining only a local card is not sufficient.
- A hidden card must be filtered from the displayed Recents stack without deleting its system task.
- `TaskKey` equality is task-instance identity, not application identity.
- Individual dismissal, Close all, and Memory Cleaner have distinct destructive paths.
- `DeleteTaskDataEvent` is destructive: it clears loader data, removes the system task, and force-stops the package.
- `deleteTaskData()` is a cache/load-queue operation and must not be confused with system task removal.
- Protected tasks must be filtered before animation callbacks, model removal, cache cleanup, system task removal, or package force-stop can occur.
- Memory Cleaner protection must be checked before any of its three destructive operations.
- If task identity cannot be resolved safely, do not classify the task as protected by a guessed package name.

## 14. Updated research status

### Newly confirmed

- Recents reload obtains the system Recent Tasks list for the current user through `ActivityManagerWrapper.getRecentTasks()`.
- The load plan reverses that list and constructs a new `TaskStack`.
- The load plan may omit tasks because of unresolved activity information, duplicate source components, configured hidden packages, or the dock activity.
- `TaskKey.equals()` uses task ID, windowing mode, and user ID.
- `deleteTaskData()` clears the loader queue and selected caches, but does not itself remove the system task.
- `DeleteTaskDataEvent` performs cache cleanup, system task removal, and package force-stop.
- MTK Memory Cleaner performs those destructive operations directly without using `DeleteTaskDataEvent`.

### Remaining research

1. Enumerate all call sites of `TaskView.dismissTask()`.
2. Trace all producers and consumers of `DeleteTaskDataEvent`.
3. Inspect `TaskStack.setTasks(List<Task>, boolean)` in full and determine how protected tasks behave during reload and reconciliation.
4. Trace the complete grid and non-grid Close all callback sequence, including task-view filtering and trigger completion.
5. Inspect all callers and entry points of MTK Memory Cleaner.
6. Trace relevant `TaskStackListener` callbacks and Recents reload behavior after system task changes.
7. Verify nullability and package/user identity for unusual tasks, including tasks without a resolved component.
8. Validate the behavior on the target MT8163 head unit running Android 9.

## 15. TaskStack reconciliation and Recents reload

Sources:

- `shared/recents/model/TaskStack.java`
- `recents/RecentsActivity.java`
- `recents/RecentsImpl.java`
- `recents/views/TaskStackView.java`
- `recents/views/RecentsView.java`

### TaskStack.setTasks()

**Confirmed** — `TaskStack.setTasks(List<Task>, boolean)` builds maps of the current and incoming raw task lists using `TaskKey`.

When notifications are enabled and callbacks are registered, it:

1. Identifies current tasks missing from the incoming list.
2. Identifies incoming tasks not present in the current list.
3. Reuses existing task objects for matching keys and updates them with `copyFrom()`.
4. Replaces the filtered stack list and raw task list with the incoming tasks.
5. Calls `onStackTaskRemoved()` for each removed task.
6. Calls `onStackTaskAdded()` for each added task.
7. Calls `onStackTasksUpdated()`.

**Confirmed** — the internal lists are replaced with the incoming list before the removal and addition callbacks are dispatched.

**Implementation constraint** — preserving a protected task only in the old in-memory stack is not sufficient. A subsequent `setTasks()` reconciliation replaces that stack from the incoming list. Any retention policy must be applied to the incoming stack before reconciliation, or through a carefully validated equivalent that preserves ordering, task identity, and callbacks.

**Important distinction** — `TaskStack.setTasks()` itself does not dispatch `DeleteTaskDataEvent` or call ActivityManager task removal. Its removal callbacks update the Recents UI/model representation. Do not equate a task disappearing during reconciliation with the destructive dismissal path.

### RecentsActivity reload path

**Confirmed** — `RecentsActivity.reloadTaskStack()`:

1. Creates a new `RecentsTaskLoadPlan`.
2. Calls `RecentsTaskLoader.preloadTasks()`.
3. Calls `RecentsTaskLoader.loadTasks()`.
4. Obtains the newly built `TaskStack`.
5. Sends `MultiWindowStateChangedEvent` with that stack.

The `TaskStackView` handles this event. In multi-window mode, or when deferred animation is not required, it calls `setTasks(event.stack, true)`. Otherwise it starts the new-stack scroll animation using the event stack.

**Confirmed** — `RecentsView.updateStack(stack, true)` also forwards the incoming stack to `TaskStackView.setTasks(stack, true)`. The normal Recents reload path therefore reaches the reconciliation behavior described above.

**Confirmed** — `RecentsActivity.onBusEvent(ActivityUnpinnedEvent)` reloads the task stack when Recents is visible. This is one explicit reload trigger; it is not the only path that can supply a new stack.

### TaskStackListener behavior

Source: `recents/RecentsImpl.java`

**Confirmed** — `RecentsImpl.TaskStackListenerImpl` is registered with `ActivityManagerWrapper.registerTaskStackListener()`.

Its `onTaskStackChangedBackground()` callback, when the overview proxy is disabled, the current-user check passes, and the svelte level is zero, preloads a fresh task plan and loads task data with cache-oriented options:

- `onlyLoadForCache = true`
- `onlyLoadPausedActivities = true`
- `loadThumbnails = true`

It updates the background layout/visibility calculation and loads task data. This callback does not directly call `TaskStack.setTasks()` on the visible Recents stack in the inspected implementation.

The listener base class also defines callbacks for task creation, removal, and moving a task to the front. The inspected `RecentsImpl.TaskStackListenerImpl` does not override those callbacks in the retrieved source range; the base implementations are empty.

**Implementation constraint** — do not assume every system task-stack notification directly reconciles the visible Recents model. Separate background cache refresh from the explicit RecentsActivity reload and UI update paths.

## 16. Updated implementation implications

- Apply **Hide from Recents** to the task list produced by the load plan, without deleting the underlying system Recent Task.
- Apply **Keep card in Recents** consistently to incoming task lists before they are reconciled with the current visible stack.
- Do not retain stale task objects solely to keep a card visible; use the newly loaded task data and preserve the incoming task order.
- If filtering changes the incoming list, ensure the empty-state UI, task-view callbacks, and stack action-button state are updated from the filtered result.
- Do not block all `onStackTaskRemoved()` callbacks: ordinary system-driven disappearance and legitimate unprotected dismissal must continue to work.
- Distinguish model reconciliation from explicit dismissal. Only the latter's destructive cleanup path should be blocked for protected tasks.
- Re-check package name and user ID on each incoming task; task IDs are transient task-instance identifiers.
- Validate both the normal Recents reload path and background task-stack refresh behavior on the target firmware.

## 17. Newly confirmed findings

- `TaskStack.setTasks()` replaces its raw and filtered task lists with the incoming list and then dispatches model/UI callbacks.
- Matching tasks are reused by `TaskKey` and updated with `copyFrom()`.
- `RecentsActivity.reloadTaskStack()` creates a fresh load plan and sends the new stack through `MultiWindowStateChangedEvent`.
- `TaskStackView` applies the incoming stack through `setTasks()` on the immediate update path.
- `RecentsView.updateStack()` also forwards incoming stacks to `TaskStackView.setTasks()`.
- `ActivityUnpinnedEvent` triggers a reload while Recents is visible.
- `RecentsImpl.TaskStackListenerImpl.onTaskStackChangedBackground()` refreshes background task data/cache and layout calculations; it does not directly reconcile the visible Recents stack in the inspected implementation.
- The base task-stack listener defines task-created, task-removed, and task-moved callbacks, but the inspected Recents implementation does not override them in the retrieved source.

## 18. Remaining research

1. Enumerate all call sites of `TaskView.dismissTask()`.
2. Trace all producers and consumers of `DeleteTaskDataEvent`.
3. Trace the complete grid and non-grid Close all callback sequence, including task-view filtering and trigger completion.
4. Inspect all callers and entry points of MTK Memory Cleaner.
5. Trace every explicit Recents reload trigger and every producer of `MultiWindowStateChangedEvent`.
6. Inspect the complete task-stack listener registration/dispatch implementation to confirm which callbacks are delivered and on which thread.
7. Verify nullability and package/user identity for unusual tasks, including tasks without a resolved component.
8. Validate the behavior on the target MT8163 head unit running Android 9.

## 19. Dismissal entry points and event chain

Sources:

- `recents/views/TaskView.java`
- `recents/views/TaskViewHeader.java`
- `recents/views/TaskStackView.java`
- `recents/views/TaskStackViewTouchHandler.java`
- `recents/views/RecentsView.java`
- `recents/RecentsActivity.java`
- `recents/events/EventBus.java`
- `recents/misc/ReferenceCountedTrigger.java`

### TaskView.dismissTask() call sites

The repository search found these Java call sites:

1. `TaskViewHeader.onClick()` — the task-card dismiss (X) button.
2. `TaskStackView.onBusEvent(PackagesChangedEvent)` — when a package/component was removed; if a corresponding view exists, it calls `dismissTask()`, otherwise it removes the task directly from the stack.
3. `TaskStackView.onBusEvent(DismissFocusedTaskViewEvent)` — dismisses the focused task view.
4. `RecentsView` launch-failure handling — if launching a task fails and its view exists, it calls `dismissTask()`.

**Implementation constraint** — `TaskView.dismissTask()` is not exclusive to user-initiated dismissal. A blanket hook there would also affect package-change cleanup and launch-failure handling. The intended Keep-card policy must distinguish user dismissal from system-driven cleanup, or intercept at a later stage with enough context.

### Swipe path

**Confirmed** — `TaskStackViewTouchHandler.canChildBeDismissed(View)` currently rejects a view only when it is already in swipe-helper animation state or its task is no longer in the stack. Otherwise it permits dismissal.

**Confirmed** — after swipe animation, `onChildDismissed(View)` sends `TaskViewDismissedEvent`. The `TaskStackView` handler for this event removes the task from the in-memory stack and sends `DeleteTaskDataEvent`.

**Candidate hook** — extend the existing swipe eligibility decision for protected tasks, preserving the original result for all other tasks. This prevents the swipe from reaching the destructive dismissal chain.

### X-button and other dismissTask() callers

**Confirmed** — the X button calls `TaskView.dismissTask()`. That method sends an animated `DismissTaskViewEvent` and registers a post-animation callback that sends `TaskViewDismissedEvent`.

The `TaskStackView` handles `DismissTaskViewEvent` by starting the selected grid/non-grid delete animation. After the animation trigger completes, the callback sends `TaskViewDismissedEvent`; the handler removes the task from the stack and sends `DeleteTaskDataEvent`.

**Important** — simply returning early from the `DismissTaskViewEvent` handler is not sufficient. The animated event's trigger still completes after dispatch, and the callback registered by `TaskView.dismissTask()` can still send `TaskViewDismissedEvent`. A prevention point must also suppress that callback or stop the dismissal before it is scheduled.

**Candidate direction** — protect the user-triggered X/focused-dismiss path before the animated dismissal is committed, while leaving package-change and launch-failure behavior intact. Do not implement a blanket `TaskView.dismissTask()` block without source discrimination.

## 20. Close All: grid and stack layouts

Source: `TaskStackView.java`, `TaskStackAnimationHelper.java`, `RecentsViewTouchHandler.java`.

**Confirmed** — the stack action button sends `DismissAllTaskViewsEvent`.

The `TaskStackView` handler snapshots the full current task list, starts the delete-all animation for the currently attached task views, and registers a post-animation callback. That callback:

1. Announces that all items were dismissed.
2. Calls `mStack.removeAllTasks(true)`.
3. Sends `DeleteTaskDataEvent` for every task in the original snapshot.

### Non-grid path

**Confirmed** — `startTaskStackDeleteAllTasksAnimation()` animates each attached task view off-screen with staggered delays. Each animation decrements the shared trigger when it ends. Once the trigger reaches zero, the registered bulk callback removes the stack contents and sends cleanup events for the snapshot.

### Grid path

**Confirmed** — `startDeleteAllTasksAnimation()` invokes `startTaskGridDeleteTaskAnimation()` for each attached task view. Each grid animation registers a last-decrement callback that calls `TaskStackViewTouchHandler.onChildDismissed()`.

That callback sends `TaskViewDismissedEvent`, whose normal handler removes the task from the stack and sends an individual `DeleteTaskDataEvent`.

The bulk post-animation callback registered by `TaskStackView.onBusEvent(DismissAllTaskViewsEvent)` is also attached to the same reference-counted trigger.

**Code-path concern requiring device/runtime validation** — in grid mode, the per-view last-decrement callbacks are registered before the bulk callback. When the trigger reaches zero, they run first and synchronously dispatch individual task-dismissal events; the bulk callback then iterates the original pre-animation task snapshot and sends another `DeleteTaskDataEvent` for those same tasks. This appears to create duplicate cleanup events for grid-visible tasks. Confirm on the target firmware before treating duplicate cleanup as a runtime fact.

**Implementation constraint** — Close All cannot be protected by blocking only `removeAllTasks(true)` or only `DeleteTaskDataEvent`. Both layout paths have animation-trigger callbacks that can dispatch individual dismissal events. The protected/unprotected partition must be applied before animation scheduling, and the post-animation cleanup must operate only on the unprotected snapshot.

## 21. Candidate interception map

| Behavior | Candidate control point | Required condition |
|---|---|---|
| Keep card during swipe | `TaskStackViewTouchHandler.canChildBeDismissed()` | Return false for protected package + user |
| Keep card on X | Before `TaskView.dismissTask()` schedules its animated event, or equivalent source-aware control | Block user X without blocking package-change/launch-failure cleanup |
| Keep card on focused dismiss | `DismissFocusedTaskViewEvent` handling before calling `dismissTask()` | Check focused task identity |
| Keep card during Close All | Filter task views and task snapshot before `startDeleteAllTasksAnimation()` | Animate and clean up only unprotected tasks |
| Prevent destructive system cleanup | `RecentsActivity.onBusEvent(DeleteTaskDataEvent)` as a final safety gate | Suppress cleanup for protected tasks only when event source is a protected dismissal; do not suppress legitimate system cleanup |

**Caution** — a global guard on `DeleteTaskDataEvent` alone may preserve a task after its UI/model entry has already been removed. It is a final destructive-operation safety gate, not a complete Keep-card implementation.

## 22. Updated research status

- Swipe eligibility and its destructive event chain are confirmed.
- X-button event sequence is confirmed.
- Additional non-X `dismissTask()` callers are identified: focused dismissal, package-change cleanup, and launch-failure cleanup.
- Close All entry point and grid/non-grid animation branches are confirmed.
- Grid mode contains a possible duplicate cleanup-event sequence that requires runtime validation.
- A blanket `TaskView.dismissTask()` hook is unsafe because the method has system-driven callers.
- A blanket `DeleteTaskDataEvent` guard is insufficient because it does not preserve the visible stack/model.

## 23. MTK Memory Cleaner entry point and execution

Source: `qs/tiles/MemoryCleanerTile.java`

### Quick Settings entry point

**Confirmed** — `QSFactoryImpl.createTileInternal()` maps the Quick Settings tile spec `memorycleaner` to `new MemoryCleanerTile(mHost)`.

**Confirmed** — `MemoryCleanerTile.handleClick()` collapses Quick Settings panels and schedules message `what == 1` with a 1500 ms delay. Its Handler then calls `clearRecentsTask()` and displays the completion toast.

This is a one-shot delayed action initiated by the user, not a periodic cleaner loop.

### Cleaner task enumeration and destructive sequence

**Confirmed** — `clearRecentsTask()` creates a fresh `RecentsTaskLoadPlan`, preloads current-user Recent Tasks using `preloadTasks(loadPlan, -1)`, loads task data, obtains the resulting `TaskStack`, and iterates its task list.

For every task, the current implementation performs this sequence without a protection check:

1. `RecentsTaskLoader.deleteTaskData(task, false)`
2. `ActivityManagerWrapper.getInstance().removeTask(task.key.id)`
3. `ActivityManager.forceStopPackage(task.key.getComponent().getPackageName())`

The cleaner does not send `DeleteTaskDataEvent` and does not route cleanup through `RecentsActivity`.

**Candidate hook** — check Protection inside the cleaner's task loop before the first destructive operation. For a protected task, skip all three operations and continue processing the remaining tasks. Preserve the existing sequence for unprotected tasks.

**Identity requirement** — use `task.key.userId` together with a safely resolved package name. Do not use task ID as the persisted setting key.

### Nullability hazard

**Confirmed** — `Task.TaskKey.getComponent()` directly calls `baseIntent.getComponent()`; it does not guard a null `baseIntent`. The cleaner then directly dereferences the returned component to obtain its package name.

Therefore, the existing cleaner assumes both a non-null base intent and a non-null explicit component. A task with a null base intent or package-only intent can fail during package resolution. The failure occurs after `deleteTaskData()` and `removeTask()` have already been requested.

**Implementation constraint** — Protection lookup must not introduce the same unchecked assumptions. Resolve package identity using a null-safe component-first, intent-package fallback. If package identity cannot be resolved, do not classify the task as protected by guessing; retain the firmware's original behavior for that task and record no package-level match.

## 24. Other task-removal APIs found in MtkSystemUI

Repository-wide search for `removeTask(` identified the following relevant uses:

- `RecentsActivity.onBusEvent(DeleteTaskDataEvent)`: destructive Recents dismissal path; deletes loader data, removes the system task, and force-stops its package.
- `MemoryCleanerTile.clearRecentsTask()`: separate destructive bulk-cleaner path; directly deletes loader data, removes the system task, and force-stops the package.
- `WorkLockActivityController`: calls `IActivityManager.removeTask(taskId)` when starting the work-lock flow fails. This is a security/work-profile error-recovery path, not a Recents dismissal or Memory Cleaner entry point.
- `TaskStack.removeTask()`: changes the in-memory Recents model and dispatches stack callbacks; it is not itself the ActivityManager system-task removal API.
- `RecentsView` dock/drag completion and `TaskStackView` package-change handling can remove tasks from the in-memory stack. These paths must not be conflated with the two confirmed bulk destructive cleanup paths.

**Finding** — within the searched MtkSystemUI Java sources, the two confirmed user-facing Recents cleanup paths that pair loader-data deletion, ActivityManager task removal, and package force-stop are `RecentsActivity.onBusEvent(DeleteTaskDataEvent)` and `MemoryCleanerTile.clearRecentsTask()`.

**Scope limitation** — this search covers the retrieved MtkSystemUI source tree, not every system service or privileged package in the firmware. It does not prove that no other package can remove Recent Tasks.

## 25. Memory Cleaner implementation constraints

- Protect tasks before `deleteTaskData()`; checking only before `forceStopPackage()` is too late.
- Skip all cleaner operations for a protected task, not only the final force-stop.
- Match settings by resolved package name and `TaskKey.userId`.
- Keep the cleaner's current-user scope; do not silently broaden it to other Android users.
- A null or unresolved package identity must not match a configured package.
- Preserve the cleaner's existing behavior for unprotected tasks.
- The 1500 ms Handler delay is only the click-to-execution delay; it is not a recurring or polling mechanism.

## 26. Updated findings and remaining validation

- The Quick Settings spec is `memorycleaner`, created by `QSFactoryImpl`.
- The tile's only discovered execution entry point is its click handler, which schedules one delayed invocation.
- Memory Cleaner independently loads current-user Recents and directly performs all three destructive operations.
- Memory Cleaner bypasses `DeleteTaskDataEvent`; a RecentsActivity-only guard cannot protect against it.
- `Task.TaskKey.getComponent()` and the cleaner's package lookup are not null-safe for package-only or incomplete task intents.
- A separate `WorkLockActivityController` task removal exists for work-lock launch failure; it is outside the intended Keep-card and Memory Cleaner policies.
- No additional MtkSystemUI Java caller pairing `deleteTaskData()`, `removeTask()`, and `forceStopPackage()` was found in the repository search.
- Remaining: inspect all `DeleteTaskDataEvent` dispatch paths and validate the proposed guards on the target Android 9 firmware.


## 27. Grid Close All callback ordering

Sources:

- `recents/views/TaskStackAnimationHelper.java`
- `recents/views/TaskStackView.java`
- `recents/misc/ReferenceCountedTrigger.java`
- `recents/events/EventBus.java`

**Confirmed from source** — the grid Close All path registers one last-decrement callback per animated `TaskView`. Each callback invokes `TaskStackViewTouchHandler.onChildDismissed()`, which dispatches `TaskViewDismissedEvent`. The `TaskStackView` handler then removes that task from the in-memory stack and sends an individual `DeleteTaskDataEvent`.

After scheduling the grid animations, `TaskStackView.onBusEvent(DismissAllTaskViewsEvent)` registers its bulk post-animation callback. That callback removes all remaining stack entries and sends another `DeleteTaskDataEvent` for every task in the original pre-animation snapshot.

**Confirmed callback ordering** — `ReferenceCountedTrigger.addLastDecrementRunnable()` appends callbacks to an ordered `ArrayList`; when the count reaches zero, `flushLastDecrementRunnables()` runs them in insertion order. The per-view grid callbacks are therefore executed before the bulk callback.

**Confirmed source-level consequence** — every task view included in the grid animation receives an individual `DeleteTaskDataEvent` before the bulk callback sends another event for the same task from its original snapshot. This is not merely a possible ordering: it follows directly from callback registration and flush order.

**Not established** — the source inspection does not establish the runtime consequences of duplicate cleanup (for example, whether repeated `removeTask()` or `forceStopPackage()` calls produce visible errors on this firmware). That requires target-device validation.

### Protection implications

- A grid Close All implementation must not allow the per-view callbacks to dismiss protected tasks.
- The bulk callback must operate on the filtered unprotected snapshot, not the original full snapshot.
- The implementation must account for the per-view callbacks and the bulk callback as separate cleanup producers.
- Do not assume that filtering only the bulk snapshot is sufficient; the grid animation callback can independently dispatch individual dismissal events.
- Keep the non-grid path distinct: its animation helper only completes the shared trigger, after which the bulk callback removes the stack and dispatches cleanup for its snapshot.

### Updated status

The previous note in Section 20 described duplicate grid cleanup as a possible sequence pending runtime confirmation. The callback order and duplicate event dispatch are now **confirmed from source**. Only the effects of repeated destructive operations remain pending device validation.


## 28. Selective Close All: stack callback behavior

Sources:

- `shared/recents/model/TaskStack.java`
- `recents/views/TaskStackView.java`
- `recents/views/TaskStackViewTouchHandler.java`

**Confirmed** — `TaskStack.removeTask(task, animation, fromDockGesture)` removes one task from both the visible stack list and raw task list, then calls `TaskStackView.onStackTaskRemoved()`.

That callback:
- returns the matching attached `TaskView` to the view pool;
- clears ignore-task state for the removed task;
- updates and relayouts the remaining stack when an animation is supplied;
- emits `AllTaskViewsDismissedEvent` only when the stack becomes empty and the removal requests dismissal.

**Confirmed** — `TaskStack.removeAllTasks(true)` has different callback semantics. It clears both task lists in one operation and invokes `onStackTasksRemoved()` once. That callback resets focus, returns every attached task view to the pool, clears all ignored tasks, and unconditionally emits `AllTaskViewsDismissedEvent`.

### Candidate selective-removal strategy

For a protected Close All operation, the following source-compatible strategy is a candidate for implementation and device validation:

1. Snapshot the complete task list and derive the unprotected task subset once at the start of the operation.
2. Build the animation input from only attached `TaskView` instances whose tasks are in that unprotected subset. Protected views must not be passed to the delete animation helper.
3. Keep the unprotected task snapshot for the final callback; do not call `removeAllTasks(true)`, because it would also remove protected tasks.
4. In the final callback, process only snapshot tasks that are still present in the stack. Grid-layout per-view callbacks have already removed their tasks through `TaskViewDismissedEvent` and dispatched their individual `DeleteTaskDataEvent`; checking stack membership avoids dispatching cleanup for those tasks a second time.
5. For each remaining unprotected task, remove it through the single-task `removeTask()` path and dispatch exactly one `DeleteTaskDataEvent`. This also covers unprotected tasks that had no attached `TaskView` and therefore had no individual grid animation callback.

**Important distinction** — the membership check is a candidate deduplication mechanism for the known synchronous EventBus path, not a concurrency guarantee. Validate it against package/task-list refreshes and other asynchronous Recents updates on the target firmware.

### Remaining risks to validate

- Whether per-task removal with `AnimationProps.IMMEDIATE` preserves the visual and accessibility behavior of the original one-shot `removeAllTasks(true)` callback in both layouts.
- Whether stack callbacks triggered during the final loop can cause re-entrant list changes; iterate over the immutable snapshot, never the live stack list.
- Whether a Close All operation with every task protected leaves the action button, focus, empty-state UI, and accessibility announcements in a correct state. It must not announce that all tasks were dismissed.
- Whether a task removed by an unrelated event after the initial snapshot but before the final callback is already fully cleaned up. Do not infer this solely from absence in the stack without tracing the competing removal path.

**Status** — the callback semantics and the difference between single-task and bulk stack removal are confirmed from source. The selective-removal strategy is a design candidate, not yet validated or approved for implementation.


## 29. Recent Tasks refresh and TaskStack.setTasks()

Sources:

- `recents/RecentsActivity.java`
- `recents/views/TaskStackView.java`
- `recents/views/RecentsView.java`
- `shared/recents/model/RecentsTaskLoadPlan.java`
- `shared/recents/model/TaskStack.java`

**Confirmed refresh flow** — `RecentsActivity.reloadTaskStack()` creates a new `RecentsTaskLoadPlan`, preloads and loads the current system task list, then dispatches `MultiWindowStateChangedEvent` with the newly built `TaskStack`.

**Confirmed update path** — `TaskStackView.onBusEvent(MultiWindowStateChangedEvent)` calls `setTasks(event.stack, true)` when entering multi-window mode or when deferred animation is not requested. The regular `RecentsView.updateStack(stack, true)` path also calls `TaskStackView.setTasks(stack, true)`.

**Confirmed TaskStack.setTasks() behavior**:

1. It creates maps of the current raw task list and incoming task list, keyed by `Task.TaskKey`.
2. Current tasks missing from the incoming list are collected as removed tasks only when callbacks are enabled.
3. Incoming tasks reuse the existing `Task` object when their key matches; `copyFrom()` refreshes its data.
4. Both internal task lists are replaced with the incoming list.
5. Each removed task triggers `TaskStackView.onStackTaskRemoved(..., AnimationProps.IMMEDIATE, false, true)`.
6. Added tasks trigger `onStackTaskAdded()`, followed by one `onStackTasksUpdated()`.

**Confirmed distinction** — this refresh-removal path does not dispatch `DeleteTaskDataEvent` and does not itself call `removeTask()` or `forceStopPackage()`. It removes the missing task from the in-memory Recents stack and returns its attached view to the pool. This is a model synchronization path, not the same operation as user dismissal.

### Protection implications

- A protected task can disappear from the visible Recents model if it is absent from the newly loaded system task list; protecting only the Swipe and Close All event handlers does not cover this path.
- Filtering only the rendered `TaskView` list is insufficient. The incoming `TaskStack` is authoritative input to `setTasks()`, and both its task identity and ordering must be considered.
- A future protection design must distinguish a genuinely removed system task from a task omitted by a refresh. Blindly merging every protected task back into the incoming list risks resurrecting stale or invalid tasks.
- The appropriate decision point is before the refreshed stack is applied, where the current stack, incoming stack, and protection state can be compared. The policy for retaining an omitted protected task still requires explicit validation.
- Matching must use the full `Task.TaskKey` identity rather than package name alone; multiple tasks may belong to the same package.

### Open questions

- Does the system task loader omit tasks because they are no longer valid, because of user/profile filtering, or due to a transient race?
- Can the protection policy safely retain a protected task that the system task manager no longer reports?
- Does a deferred `startNewStackScrollAnimation()` path eventually apply the incoming stack through another callback, and at what point relative to its animation trigger?
- Which package/task callbacks can race with a refresh and legitimately remove a protected task?

**Status** — refresh dispatch, stack diffing, object reuse, and callback behavior are confirmed from source. The policy for retaining protected tasks across refreshes is unresolved and must not be implemented by unconditional list merging.


## 30. Deferred stack animation and package-change removal

Sources:

- `recents/views/TaskStackAnimationHelper.java`
- `recents/views/TaskStackView.java`
- `recents/RecentsActivity.java`
- `shared/recents/model/TaskStack.java`
- `shared/recents/model/RecentsTaskLoader.java`

### Deferred stack replacement

**Confirmed:** for the animated (non-multi-window, non-empty) `MultiWindowStateChangedEvent` path, `TaskStackView` posts a runnable that calls `TaskStackAnimationHelper.startNewStackScrollAnimation()`.

That method:

1. Reads the incoming stack's task list and captures current task transforms.
2. Calls `TaskStackView.setTasks(newStack, false)`.
3. Recalculates layout, binds visible task views, and animates the new stack into its initial position.

The `false` argument is significant: `TaskStack.setTasks()` replaces both internal task lists, but disables the added/removed task callbacks and the final `onStackTasksUpdated()` callback. Therefore tasks absent from this refreshed stack can disappear from the model without passing through `onStackTaskRemoved()`.

This is a second refresh behavior in addition to the callback-enabled path documented in Section 29. A protection mechanism that relies only on `onStackTaskRemoved()` will miss this deferred replacement path.

### Package-change path

**Confirmed event flow:**

1. `RecentsActivity.onPackageChanged(packageName, userId)` invalidates cached activity information in `RecentsTaskLoader`.
2. It dispatches `PackagesChangedEvent`.
3. `TaskStackView.onBusEvent(PackagesChangedEvent)` calls `TaskStack.computeComponentsRemoved(packageName, userId)`.
4. The method checks components belonging to that package and user. Components whose `ActivityInfo` can no longer be resolved are classified as removed.
5. Every stack task whose component is in that removed-component set is removed:
   - If an attached `TaskView` exists, `dismissTask()` starts the normal dismissal flow.
   - If no attached view exists, `TaskStack.removeTask(..., AnimationProps.IMMEDIATE, false)` removes it directly.

The loader's `onPackageChanged()` only invalidates cached `ActivityInfo` entries for the package; it does not itself remove tasks.

### Cleanup distinction

- Attached-view branch: `dismissTask()` proceeds through the dismissal event path, which ultimately dispatches `DeleteTaskDataEvent`.
- No-view branch: direct `TaskStack.removeTask()` invokes the stack callback but does not dispatch `DeleteTaskDataEvent` in this handler.
- Neither branch calls `forceStopPackage()` from this package-change handler.

Thus package-change removal is not equivalent to Close All or an ordinary user swipe, and the two branches have different cleanup-event behavior.

### Protection implications

- The deferred refresh path can replace the stack while explicitly suppressing removal callbacks. Hooking only `onStackTaskRemoved()` is insufficient.
- Package-change removal is a separate path that can dismiss a task because its component is no longer resolvable. A protected-card policy must not blindly block this: after uninstall or component removal, retaining the card may produce a stale entry.
- The no-attached-view branch proves that not every task removal is represented by a `TaskViewDismissedEvent` or followed by `DeleteTaskDataEvent`.
- Any future interception must account for package name **and user ID**, and compare component identity; package-only matching is too broad.
- Whether a package update temporarily makes `ActivityInfo` unresolved, and whether this produces transient false removals on this firmware, is not established by static source inspection.

### Remaining validation

- Trace the exact package-monitor callbacks for package update, replacement, removal, and component changes on the target Android 9 build.
- Confirm whether the no-view direct removal is followed by cleanup elsewhere.
- Test whether the deferred animation path can run while a protected task is omitted from the incoming system task list.
- Decide which system-level removals must override Protection (uninstall, user/profile removal, invalid component) before designing retention rules.

**Status** — both the deferred stack replacement and package-change removal branches are confirmed from source. Runtime race behavior and the retention policy for genuinely invalid tasks remain unresolved.
