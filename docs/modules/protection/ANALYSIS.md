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

**Completed in Section 33** — the complete cleaner method, task-list construction, processing order, and available Java entry point have been traced. Runtime behavior remains pending.

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
5. **Completed in Section 33:** inspect the complete Memory Cleaner method, task-list construction, destructive sequence, and Java entry points.
6. Trace relevant `TaskStackListener` callbacks and Recents reload behavior after system task changes.
7. Verify nullability and package/user identity for unusual tasks, including tasks without a resolved component.
8. Validate the behavior on the target MT8163 head unit running Android 9.

## 10. Research status

### Confirmed from inspected source

- Swipe dismissal eligibility is checked by `TaskStackViewTouchHandler.canChildBeDismissed()`.
- The card header X action calls `TaskView.dismissTask()`.
- Close all uses a separate bulk-dismiss path with grid and non-grid animations.
- `DeleteTaskDataEvent` deletes task data, removes the system task, and force-stops the package.
- Memory Cleaner independently deletes task data, removes system tasks, and force-stops packages; its complete selection and execution flow is documented in Section 33.
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


## 31. DismissTask call-site audit and Close All event fan-out

Sources:

- `recents/views/TaskView.java`
- `recents/views/TaskViewHeader.java`
- `recents/views/TaskStackView.java`
- `recents/views/RecentsView.java`
- `recents/views/TaskStackAnimationHelper.java`
- `recents/events/EventBus.java`
- `recents/misc/ReferenceCountedTrigger.java`
- `recents/RecentsActivity.java`

### TaskView.dismissTask() call sites

**Confirmed** — the source search found these call sites:

1. `TaskViewHeader.onClick()` invokes it for the card X button.
2. `TaskStackView.onBusEvent(DismissFocusedTaskViewEvent)` invokes it for the currently focused task when a focused-dismiss event is received.
3. `TaskStackView.onBusEvent(PackagesChangedEvent)` invokes it when the task's component is no longer present in the package's resolved component set and the task has an attached `TaskView`.
4. `RecentsView.lambda$startTaskActivity$2()` invokes it when launching a task fails and a `TaskView` is available.

**Implementation constraint** — intercepting `TaskView.dismissTask()` and unconditionally cancelling it for protected packages would also block package-change cleanup and failed-launch cleanup. The X-button path is only one caller. The focused-dismiss event is another caller whose initiating UI/input path still needs tracing.

### DeleteTaskDataEvent producers and consumer

**Confirmed** — the two producer paths in the inspected source are:

- `TaskStackView.onBusEvent(TaskViewDismissedEvent)`: removes the task from the in-memory stack, then sends one `DeleteTaskDataEvent`.
- `TaskStackView.onBusEvent(DismissAllTaskViewsEvent)`: after the animation trigger completes, removes all tasks from the stack and sends one `DeleteTaskDataEvent` for every task in its pre-animation snapshot.

**Confirmed** — `RecentsActivity.onBusEvent(DeleteTaskDataEvent)` is the destructive consumer. It clears loader task data, requests system task removal, and force-stops the task package.

The MTK Memory Cleaner remains a separate direct cleanup path and does not use this event.

### Close All animation and trigger sequence

**Confirmed** — `TaskStackAnimationHelper.startDeleteAllTasksAnimation()` selects two different implementations:

- Grid layout: invokes `startTaskGridDeleteTaskAnimation()` for each supplied `TaskView`. Each grid animation registers a last-decrement callback that calls `TaskStackViewTouchHandler.onChildDismissed()`.
- Non-grid stack layout: invokes `startTaskStackDeleteAllTasksAnimation()`. This animates each supplied view off-screen, restores its clipping state at animation end, and does not call `onChildDismissed()`.

**Confirmed** — `DismissAllTaskViewsEvent` is an `EventBus.AnimatedEvent`. EventBus increments its trigger before dispatch and decrements it after synchronous event handlers return. The grid animation helper registers its per-view last-decrement callbacks before `TaskStackView` registers the bulk post-animation callback.

**Confirmed call sequence in grid layout** — when the final animation decrement and the EventBus post-dispatch decrement bring the trigger to zero, `ReferenceCountedTrigger.flushLastDecrementRunnables()` runs the registered callbacks in insertion order. The per-view callbacks call `onChildDismissed()`; that sends `TaskViewDismissedEvent` synchronously. `TaskStackView.onBusEvent(TaskViewDismissedEvent)` then removes the individual task and sends its own `DeleteTaskDataEvent`. After those callbacks, the bulk callback runs, calls `removeAllTasks(true)`, and sends `DeleteTaskDataEvent` again for every task in the original snapshot.

**Finding / risk** — the inspected grid Close All path therefore schedules both per-task dismissal cleanup and a second bulk cleanup for the same pre-animation tasks. This can dispatch duplicate `DeleteTaskDataEvent` instances and repeat the consumer's cache deletion, system task removal request, and package force-stop request. The exact runtime impact of repeated system operations is not established, but the duplicate event dispatch is directly implied by the source callback order.

**Confirmed non-grid distinction** — the non-grid animation path does not register the per-view `onChildDismissed()` callbacks. Its inspected Close All path reaches the bulk callback, which removes the stack and sends one event per snapshotted task.

### Protection implications

- Do not use `TaskView.dismissTask()` as a blanket Protection gate without distinguishing its caller and reason.
- Do not assume grid and non-grid Close All have equivalent callback sequences.
- A future Close All filter must keep the task snapshot and animated `TaskView` collection consistent. Filtering only the final `removeAllTasks()` call is too late because the views have already been animated.
- The grid path needs special handling to avoid both deleting protected tasks and emitting duplicate cleanup for unprotected tasks.
- The event fan-out and trigger ordering must be preserved or deliberately replaced when designing a filtered Close All flow.

### Remaining research

1. Trace the producer and input/UI path for `DismissFocusedTaskViewEvent`.
2. Determine whether the grid Close All duplicate event sequence is an intentional firmware behavior or an implementation defect; validate on-device before deciding whether Protection should compensate for it.
3. Trace all entry points that can initiate `DismissAllTaskViewsEvent`, including stack action button and keyboard/accessibility paths.
4. Inspect the full package-monitor lifecycle to distinguish uninstall/component removal from transient package-update resolution gaps.

**Status** — all four `dismissTask()` call sites, both `DeleteTaskDataEvent` producer paths, the destructive consumer, and the grid/non-grid Close All callback distinction are confirmed from source. Runtime validation and the focused-dismiss event origin remain pending.


## 32. Dismiss event input origins and system task-stack callbacks

Sources:

- `recents/RecentsActivity.java`
- `recents/views/RecentsViewTouchHandler.java`
- `shared/system/TaskStackChangeListener.java`
- `shared/system/TaskStackChangeListeners.java`
- `recents/RecentsImpl.java`

### Focused-task dismissal input

**Confirmed** — `RecentsActivity.onKeyDown()` dispatches `DismissFocusedTaskViewEvent` when the key is not handled by the Tab or arrow-key navigation branches and the key event is not a repeat. The explicit dismissal key codes are:

- `KEYCODE_DEL` (67, Backspace);
- `KEYCODE_FORWARD_DEL` (112, Delete).

The event is then handled by `TaskStackView.onBusEvent(DismissFocusedTaskViewEvent)`, which calls `dismissTask()` for the focused task when its `TaskView` is attached.

**Implementation constraint** — the focused-dismiss path is a keyboard/input dismissal path, not the X-button path. Protection must preserve the expected behavior for unprotected focused tasks and must not assume that every `dismissTask()` call originated from the header button.

### Close All input origin

**Confirmed** — the source search found one producer of `DismissAllTaskViewsEvent`: `RecentsViewTouchHandler.handleTouchEvent()`. It dispatches the event when the stack action button is pressed and the pointer is released or cancelled while still inside the button bounds.

No keyboard producer of `DismissAllTaskViewsEvent` was found in the inspected source tree.

The action button event is separate from focused-task dismissal and from individual card dismissal.

### System task-stack callbacks

**Confirmed** — `TaskStackChangeListeners` receives ActivityManager task-stack callbacks and forwards them to registered `TaskStackChangeListener` instances through its Handler. Its `onTaskRemoved(taskId)` callback is forwarded to listeners as a task-ID notification.

**Confirmed** — the base `TaskStackChangeListener.onTaskRemoved(int)` implementation is empty, and the inspected `RecentsImpl.TaskStackListenerImpl` does not override it.

**Confirmed** — `RecentsImpl.TaskStackListenerImpl.onTaskStackChangedBackground()` reloads the system Recent Tasks list into a new load plan and loads task data/thumbnails for cache use when the overview proxy is disabled, the current user check passes, the window bounds are valid, and `svelteLevel == 0`. It does not directly replace the visible `RecentsActivity` stack in this callback.

**Implementation constraint** — do not treat the framework `onTaskRemoved(taskId)` notification as an interception point for preventing task removal. In this source, it is a post-change notification with no default removal action in the listener base class. It also carries only a task ID, so package/user matching would require a separately validated lookup.

### Updated event map

| Operation | Entry point | Main event/path |
|---|---|---|
| Card X | `TaskViewHeader.onClick()` | `TaskView.dismissTask()` |
| Focused task key dismissal | `RecentsActivity.onKeyDown()` | `DismissFocusedTaskViewEvent` |
| Swipe dismissal | `TaskStackViewTouchHandler` / `SwipeHelper` | `TaskViewDismissedEvent` |
| Close All | `RecentsViewTouchHandler` stack action button | `DismissAllTaskViewsEvent` |
| Package/component change | `RecentsActivity.onPackageChanged()` | `PackagesChangedEvent` |
| Failed task launch | `RecentsView.lambda$startTaskActivity$2()` | `TaskView.dismissTask()` |
| MTK Memory Cleaner | `MemoryCleanerTile.clearRecentsTask()` | Direct loader/system cleanup calls |
| System task-stack notification | ActivityManager callback | `TaskStackChangeListener.onTaskRemoved(taskId)` / `onTaskStackChanged()` |

**Status** — focused-dismiss keyboard origin, Close All stack-button origin, and the distinction between post-removal task-stack notifications and active Recents mutation are confirmed from source. Target-device input behavior remains untested.

## 33. MTK Memory Cleaner task selection and destructive sequence

Sources:

- `qs/tiles/MemoryCleanerTile.java`
- `qs/tileimpl/QSFactoryImpl.java`
- `shared/recents/model/RecentsTaskLoadPlan.java`
- `shared/recents/model/RecentsTaskLoader.java`
- `shared/recents/model/Task.java`
- `shared/system/ActivityManagerWrapper.java`

### Entry point and execution timing

**Confirmed** — `QSFactoryImpl.createTile()` constructs `MemoryCleanerTile` for the Quick Settings tile spec `memorycleaner`. The inspected source contains no other Java call site for `clearRecentsTask()`; it is invoked by the tile's private Handler after a click.

**Confirmed** — `handleClick()` first calls `mHost.collapsePanels()`, then posts Handler message `what == 1` with a 1,500 ms delay. The Handler invokes `clearRecentsTask()` and displays the completion Toast after the method returns.

The delay is a fixed UI sequencing delay. It is not a task-state or animation-completion callback. The inspected code does not cancel the pending message when the tile is no longer visible.

### Task-list construction

**Confirmed** — each invocation creates a new `RecentsTaskLoadPlan` and calls:

```java
loader.preloadTasks(loadPlan, -1);
```

The two-argument loader overload supplies `ActivityManagerWrapper.getInstance().getCurrentUserId()`. The load plan obtains up to `ActivityManager.getMaxRecentTasksStatic()` system Recent Tasks for that user and reverses the returned list.

The plan converts the raw entries into a new `TaskStack`. The inspected conversion logic:

1. Creates each `TaskKey` from the persistent task ID, windowing mode, base intent, source component, user ID, and last-active time.
2. Resolves `ActivityInfo` through `RecentsTaskLoader`; entries with no resolved `ActivityInfo` are not added.
3. Skips a task when an earlier accepted task has the same top component.
4. Skips packages listed in `R.array.recents_task_hidden_list`.
5. Skips the current Dock activity.
6. Adds the remaining tasks to the new stack. Freeform tasks are marked as non-stack tasks but are not excluded by that condition alone.

**Confirmed** — the cleaner then creates `RecentsTaskLoadPlan.Options`, copies `launchedNumVisibleTasks` and `launchedNumVisibleThumbnails` from the Recents launch state, calls `loader.loadTasks()`, and reads `loadPlan.getTaskStack().getTasks()`.

The Options defaults are `loadIcons = true`, `loadThumbnails = false`, and `onlyLoadForCache = false`. The visible-task counts affect the loading plan; they do not limit the subsequent cleaner loop. The loop iterates from index zero through the full resulting task-list size.

**Implementation constraint** — Protection must evaluate the actual `Task` objects in this newly generated stack, not only currently attached `TaskView` instances or the visible-task counts. The plan is built from the current system list and can differ from the Recents Activity's currently displayed stack.

### Per-task destructive sequence

**Confirmed** — for every task in the generated stack, the cleaner performs these operations in this exact order:

1. `loader.deleteTaskData(task, false)`
2. `ActivityManagerWrapper.getInstance().removeTask(task.key.id)`
3. Obtains `ActivityManager` from the tile context and calls `forceStopPackage(task.key.getComponent().getPackageName())`

There is no `DeleteTaskDataEvent` dispatch in this method. The cleaner therefore bypasses the Recents EventBus cleanup consumer entirely.

**Confirmed** — `deleteTaskData(task, false)` removes the task from the background resource-load queue and removes its icon, activity-label, and content-description cache entries. Because the boolean is false, it does not call `task.notifyTaskDataUnloaded()`. This method does not itself remove the system Recent Task or force-stop the package.

**Confirmed** — `ActivityManagerWrapper.removeTask(taskId)` submits the system `ActivityManager.getService().removeTask(taskId)` call to its background executor. The wrapper catches and logs `RemoteException`; it does not synchronously report success to the cleaner.

**Confirmed** — the final `forceStopPackage()` call is issued directly by the tile, not by the wrapper or a Recents event handler. The call passes a package name and no explicit user ID.

### Identity and failure risks

**Confirmed** — the cleaner obtains the package using `task.key.getComponent().getPackageName()`. `TaskKey.getComponent()` returns `baseIntent.getComponent()`; it does not fall back to `sourceComponent` or `Intent.getPackage()`.

**Confirmed null-safety risk** — the inspected `TaskKey.getComponent()` dereferences `baseIntent`, and the cleaner then dereferences the returned component. If either is null, the loop can throw before completing the current task's force-stop call and before processing later tasks. The source does not guard these values or catch exceptions around each task.

**Implementation constraint** — Protection matching must not reuse this unguarded package lookup. Validate `task`, `task.key`, `baseIntent`, and the available component/package identity before evaluating the protected-app policy. For an unknown identity, do not allow the cleaner to proceed with destructive operations on that task.

### Protection implications

- A filter only on `DeleteTaskDataEvent`, `TaskView.dismissTask()`, or Recents UI gestures cannot protect tasks from this cleaner.
- The protection decision must occur before `deleteTaskData()`; filtering only `removeTask()` or `forceStopPackage()` would still erase Recents cache state for a protected task.
- Apply the decision independently to every task in the generated stack. Preserve the original three-operation order for unprotected tasks.
- Use package name plus Android user ID for the persistent protection policy. The cleaner's force-stop call does not pass a user ID, while the task key does contain one.
- The cleaner operates on a fresh system-derived task list, not the currently attached UI cards. A task with no attached `TaskView` can still be cleaned.
- The Toast is displayed after the method returns; it does not verify that asynchronous system task removal succeeded.

### Research status

**Confirmed from source** — the cleaner entry point, 1.5-second delayed execution, current-user task-list acquisition, task-list filtering, full-stack iteration, cache deletion, asynchronous system task removal, and direct package force-stop sequence are now traced.

**Pending runtime validation** — verify on the target Android 9 firmware how `removeTask()` and `forceStopPackage()` behave for protected tasks, multiple tasks from one package, work/profile users, unresolved components, and a task list that changes during the cleaner loop. Static source inspection cannot establish the system-service outcome or timing of these operations.

## 34. Full audit of TaskStack mutation and system task-removal call sites

Sources audited:

- `shared/recents/model/TaskStack.java`
- `recents/views/TaskStackView.java`
- `recents/views/RecentsView.java`
- `recents/views/TaskStackAnimationHelper.java`
- `recents/RecentsActivity.java`
- `recents/RecentsImpl.java`
- `shared/system/ActivityManagerWrapper.java`
- `shared/recents/model/RecentsTaskLoader.java`
- `qs/tiles/MemoryCleanerTile.java`
- `keyguard/WorkLockActivityController.java`
- Corresponding APKTool smali call sites were cross-checked where present.

### `TaskStack.removeTask()` call sites

| Call site | Trigger / purpose | Model mutation | System task removal / force-stop |
|---|---|---|---|
| `TaskStackView.onBusEvent(TaskViewDismissedEvent)` | Swipe or X dismissal | Removes one task from the active stack | Dispatches `DeleteTaskDataEvent`; `RecentsActivity` handler deletes loader data, asynchronously calls `ActivityManager.removeTask()`, then calls `forceStopPackage()` |
| `TaskStackView.onBusEvent(PackagesChangedEvent)`, no attached `TaskView` branch | Package/component is no longer resolvable | Removes one task with `AnimationProps.IMMEDIATE` | No `DeleteTaskDataEvent` in this branch |
| `RecentsView.lambda$onBusEvent$1(DragEndEvent)` | Successful drag of a task into docked/split-screen mode, after app transition animation starts | Removes the task from the Recents model | No `DeleteTaskDataEvent` and no explicit system `removeTask()`; the task has been launched into the docked mode |
| `TaskStack.removeTask()` overload | Internal model API | Removes from visible stack list and raw task list; calls `onStackTaskRemoved()` only when the task is in the stack-task list | No direct system service call |

**Confirmed** — the three application-level call sites above have different semantics. Only the ordinary `TaskViewDismissedEvent` path dispatches the destructive `DeleteTaskDataEvent` cleanup. The package-change no-view branch is a model-only removal in the inspected handler. The dock transition is a model removal after a successful launch, not a request to destroy the task.

### `TaskStack.removeAllTasks()` call sites

| Call site | Argument | Purpose | Side effects |
|---|---:|---|---|
| `TaskStackView` Close All post-animation callback | `true` | Clears the active Recents stack | Calls `onStackTasksRemoved()`; the callback then dispatches one `DeleteTaskDataEvent` for each task in the saved snapshot |
| `RecentsImpl.updateHeaderBarLayout()` | `false` | Clears the temporary dummy stack before measuring a replacement stack | No stack callback; does not dispatch cleanup events or remove system tasks |

**Confirmed** — only the first call site is the user-facing bulk task dismissal path. The second operates on `mDummyStackView`, used for header-bar layout measurement, and must not be confused with clearing the user's Recent Tasks.

### `TaskStack.setTasks()` call sites and behavior

| Call site | Notification flag | Purpose |
|---|---:|---|
| `RecentsTaskLoadPlan.preloadPlan()` | `false` | Builds a new stack from the system Recent Tasks list |
| `RecentsView.updateStack()` → `TaskStackView.setTasks()` | `true` requested; effective only when layout is initialized | Replaces the active Recents stack during normal stack updates |
| `TaskStackAnimationHelper.startNewStackScrollAnimation()` → `TaskStackView.setTasks()` | `false` | Replaces the active stack as part of deferred stack animation |
| `RecentsImpl.updateHeaderBarLayout()` → dummy `TaskStackView.setTasks()` | `false` | Supplies tasks to the temporary layout-measurement stack |
| `RecentsImpl.startRecentsActivity()` → dummy `TaskStackView.setTasks()` | `false` | Clears the temporary dummy stack before starting Recents |

**Confirmed** — `TaskStack.setTasks()` always replaces both internal task lists with the incoming list. With notifications enabled and a callback registered, it calculates removed and added tasks, reuses existing Task objects for matching `TaskKey` values, calls removal/addition callbacks, and finally calls `onStackTasksUpdated()`.

**Confirmed** — when notifications are disabled, omitted tasks are not passed through `onStackTaskRemoved()`; they simply disappear from the replaced model lists. This is the deferred-animation behavior already described in Section 30 and the initial-load behavior in Section 29.

**Confirmed** — `TaskStackView.setTasks()` additionally gates the notification flag on `mLayoutAlgorithm.isInitialized()`. A caller passing `true` does not guarantee removal callbacks if the layout is not initialized.

**Important distinction** — neither `removeTask()`, `removeAllTasks()`, nor `setTasks()` intrinsically calls ActivityManager or force-stops a package. System destruction is performed by separate paths, notably the `DeleteTaskDataEvent` consumer and Memory Cleaner.

### Additional direct system `removeTask(taskId)` call site

**Confirmed** — `WorkLockActivityController.startWorkChallengeInTask(taskId,userId)` obtains task description and attempts to launch the work-profile credential challenge as a task overlay. If `startActivityAsUser()` returns an unsuccessful result, it directly calls `IActivityManager.removeTask(taskId)`. This is a failure-cleanup path for the work challenge, not a Recents gesture or Close All path. It does not call the Recents EventBus cleanup handler or `forceStopPackage()` in the inspected method.

The other direct system task-removal route is `ActivityManagerWrapper.removeTask(taskId)`, which submits `ActivityManager.getService().removeTask(taskId)` to its background executor. Its known callers include the `DeleteTaskDataEvent` handler and Memory Cleaner. The method's asynchronous nature means its return to the caller is not confirmation that the system removed the task.

### Audit conclusions for Protection

1. Intercepting only `TaskStack.removeTask()` is insufficient: Memory Cleaner and WorkLock can remove system tasks without using it.
2. Intercepting only `ActivityManagerWrapper.removeTask()` is insufficient: WorkLock calls `IActivityManager.removeTask()` directly.
3. Intercepting `DeleteTaskDataEvent` is insufficient: Memory Cleaner performs loader-cache deletion and system cleanup directly; WorkLock has its own failure cleanup.
4. Intercepting `TaskStack.setTasks()` or model removal callbacks does not prevent system task destruction. These are model synchronization paths and can also omit tasks without callbacks.
5. Docking a task is a model removal but must not be treated as destructive dismissal; the task is being launched into split-screen/docked mode.
6. Package-change removal without an attached view currently has no explicit loader-data/system-task cleanup in the inspected handler. Do not assume that every model removal has the same cleanup semantics.
7. `WorkLockActivityController` is a distinct system workflow. Whether Protection should preserve a work-profile task when its credential challenge cannot launch is a policy decision requiring explicit scope confirmation; it should not be silently blocked.

### Research status

**Source audit completed** — all indexed JADX-source call sites for `TaskStack.removeTask()`, `removeAllTasks()`, and `setTasks()` were classified, with smali cross-checks for the corresponding compiled methods. Direct system `removeTask(taskId)` paths outside TaskStack were also identified.

**Pending** — audit all producers and consumers of `DeleteTaskDataEvent` and trace the task-stack/package/user callbacks that can invalidate a task independently of Recents UI. Device validation is still required for timing and actual system-service outcomes.

## 35. DeleteTaskDataEvent and system task-stack callback audit

Sources:

- `recents/events/ui/DeleteTaskDataEvent.java`
- `recents/views/TaskStackView.java`
- `recents/RecentsActivity.java`
- `recents/events/EventBus.java`
- `shared/system/TaskStackChangeListener.java`
- `shared/system/TaskStackChangeListeners.java`
- `recents/RecentsImpl.java`
- `statusbar/phone/NavigationBarFragment.java`
- `recents/misc/SysUiTaskStackChangeListener.java`

### DeleteTaskDataEvent producers and consumer

**Confirmed** — the JADX source search found two producers of `DeleteTaskDataEvent`:

1. `TaskStackView.onBusEvent(TaskViewDismissedEvent)` dispatches one event after removing the dismissed task from the in-memory stack. This is the common individual card dismissal path (Swipe/X).
2. `TaskStackView.onBusEvent(DismissAllTaskViewsEvent)` captures a snapshot of all tasks before animation, then dispatches one event for every task in that snapshot after the Close All animation callback.

**Confirmed** — the only consumer found in the inspected JADX source tree is `RecentsActivity.onBusEvent(DeleteTaskDataEvent)`.

The consumer performs this exact sequence:

1. `Recents.getTaskLoader().deleteTaskData(event.task, false)`
2. `ActivityManagerWrapper.getInstance().removeTask(event.task.key.id)`
3. `ActivityManager.forceStopPackage(event.task.key.getComponent().getPackageName())`

The first operation synchronously clears loader queue/cache state. The second schedules system task removal on the wrapper's background executor. The third directly requests package force-stop. The consumer does not wait for the asynchronous task-removal result.

**Confirmed** — the event contains a `Task` reference, not a stable task ID/package/user tuple independent of that object. The consumer dereferences `event.task.key.getComponent()` without null checks. This repeats the component/base-intent null-safety risk identified for Memory Cleaner in Section 33.

**Confirmed** — the consumer's force-stop call supplies a package name but no explicit user ID. The originating TaskKey does carry a user ID. A Protection policy must therefore evaluate user identity before allowing the event to reach this consumer; package-only filtering is not sufficient for multi-user correctness.

### EventBus implications

**Confirmed** — `DeleteTaskDataEvent` is a normal EventBus event, not an animated event with its own completion callback. The animation synchronization happens in the Close All event's `ReferenceCountedTrigger` callback before the individual delete events are dispatched.

**Implementation constraint** — cancelling or filtering the event must happen before `RecentsActivity.onBusEvent(DeleteTaskDataEvent)` executes. Filtering after the consumer begins is too late because loader cache deletion occurs before the asynchronous system task-removal request and package force-stop.

**Confirmed** — no other producer or consumer was found in the inspected JADX source tree. APKTool smali contains the corresponding compiled event producer and consumer methods, consistent with the JADX findings.

### Package lifecycle callbacks

**Confirmed** — `RecentsActivity` registers a `PackageMonitor` whose inspected callbacks are:

- `onPackageRemoved(packageName, uid)`
- `onPackageChanged(packageName, uid, components)`
- `onPackageModified(packageName)`

Each forwards the package name and `getChangingUserId()` to `RecentsActivity.onPackageChanged(packageName,userId)`.

That method first calls `RecentsTaskLoader.onPackageChanged(packageName)`, then dispatches `PackagesChangedEvent(packageName,userId)`.

**Confirmed** — the loader method invalidates cached `ActivityInfo` entries for components in the changed package. The Recents event handler then checks package components against the supplied user and removes tasks whose components no longer resolve. This path is a reaction to package lifecycle notifications, not a generic task-dismissal request.

**Important limitation** — the inspected PackageMonitor does not override `onPackageAdded()`; do not infer an explicit added-package callback from this class. Package update/replace notification ordering and transient resolution failures still require Android 9 device validation.

### TaskStackChangeListener callbacks

**Confirmed** — `TaskStackChangeListeners` receives ActivityManager task-stack callbacks and dispatches them to registered `TaskStackChangeListener` instances. `onTaskRemoved(taskId)` is queued to the listener Handler and delivered after the system callback has been received.

**Confirmed** — the base `TaskStackChangeListener.onTaskRemoved(int)` method is empty. In the inspected SystemUI source, the only concrete override found is in `NavigationBarFragment`, where it resets the rotate-suggestion button state. The Recents task-stack listener does not override `onTaskRemoved()`.

**Implementation constraint** — `onTaskRemoved(taskId)` is a post-removal notification, not a veto/interception point. It provides only the task ID, not the package/user identity required for Protection policy matching.

**Confirmed** — `RecentsImpl.TaskStackListenerImpl.onTaskStackChangedBackground()` may reload a new Recent Tasks plan and load task data into caches when the overview proxy is disabled, the current-user check passes, the window rectangle is valid, and `svelteLevel == 0`. It does not directly mutate the visible RecentsActivity stack in this callback. This is a background cache-refresh path, consistent with the earlier task-refresh analysis.

**Confirmed** — the same listener dispatcher also invokes other SystemUI listeners for task-stack changes (keyguard, navigation/status bar, PiP, work-lock, onboarding, and split-screen UI). These callbacks serve their respective UI/system functions; no additional Recents task-removal veto was found in the inspected listeners.

### Protection interception map update

| Path | Can prevent removal before destructive action? | Notes |
|---|---|---|
| `DeleteTaskDataEvent` consumer | Yes, if filtered before consumer execution | Covers ordinary Swipe/X and Close All cleanup |
| Memory Cleaner loop | Yes, only before `deleteTaskData()` | Bypasses EventBus entirely |
| WorkLock direct `IActivityManager.removeTask()` | Separate direct path | Whether it is in Protection scope requires a policy decision |
| `TaskStack.removeTask()` | Model mutation only | Not a universal system-removal interception point |
| `TaskStackChangeListener.onTaskRemoved()` | No | Post-removal notification |
| PackageMonitor / `PackagesChangedEvent` | Not a generic task-removal veto | Handles package/component invalidation |
| `TaskStack.setTasks()` refresh | Not by itself | Replaces Recents model; callback behavior depends on notification and layout initialization |

### Research status

**Confirmed from source** — all `DeleteTaskDataEvent` producers and consumers found in JADX sources are classified; package monitor callbacks and task-stack listener dispatch behavior are traced.

**Pending** — inspect the complete set of direct ActivityManager task-removal APIs and any vendor-specific cleanup paths outside the Recents package; validate package replacement, user/profile boundaries, and system callback timing on the target Android 9 firmware.

## 36. Vendor CarServices package force-stop paths outside Recents

Scope: this section records firmware-level package termination paths found outside `MtkSystemUI`. These are not Recents-card deletion paths, but they can independently stop an application's process. They must not be conflated with Protection's current Recents dismissal scope.

Sources:

- `artifacts/CarServises/jadx/boot-carservices_classes/sources/android/utilities/Util.java`
- `android/link/CarletterInterface.java`
- `android/link/CarZlinkInterface.java`
- `android/sourceservice/SourceService.java`
- `android/carstatus/CarStatusService.java`
- `android/carsource/McuService.java`

### Shared force-stop helpers

**Confirmed** — `Util.forceStopPackage(String)` clears Binder calling identity, calls `ActivityManager.getService().forceStopPackage(pkg, -1)`, catches `RemoteException`, and restores the original calling identity.

**Confirmed** — `SourceService.forceStopPackage(String)` resolves an application identifier through `Util.findPackageName()`, then invokes the same system service method with user argument `-1`. It also clears/restores Binder calling identity.

Both helpers act on packages, not individual Recent Tasks. They do not call `ActivityManager.removeTask()` or mutate the Recents `TaskStack` directly.

### Confirmed callers

| Component | Trigger | Packages / target selection | Purpose |
|---|---|---|---|
| `CarletterInterface.stopCarletter()` | Its internal Handler message case 2 | LT CarPlay, Huawei Nearby, Huawei projection and related packages | Stops the Carletter connection stack |
| `CarZlinkInterface.sendAccState(0)` | MCU ACC state changes to off | Active ZLink package | Stops ZLink during power-off handling |
| `CarZlinkInterface` restart branch | Configuration/restart path when `restart == true` | Active ZLink package | Restarts ZLink |
| `SourceService.setOnActivityResume(pkg)` | Activity-resume source arbitration | Competing CarPlay / EasyConnect packages | Stops competing projection applications when another source becomes active |
| `SourceService` Handler message case 4 | Internal queued kill request | App identifier in `msg.obj`, except entries in `SYSTEM_PACKAGES` and `CurrentSource` | Stops an app that is not the active source |
| `CarStatusService.findAndkillbyUid()` | Wake-lock cleanup branch | Package resolved from a WorkSource UID greater than 10000 | Stops a package associated with a selected wake-lock entry |

**Confirmed** — `CarStatusService.checkWakeLock(clean)` reads the PowerManager wake-lock list and invokes `findAndkillbyUid()` for entries when `clean` is true. Its handler calls this check with `mCheckWakeLockTimes > 15` and schedules another check after 5 seconds. This is a separate vendor wake-lock cleanup mechanism, not a Recents action.

**Confirmed** — `McuService` contains a private `forceStopPackage(String)` helper that calls `forceStopPackage(app,-1)`. The inspected source search found the helper definition but no caller in that class; it is not counted as an active call path without further evidence.

### Protection scope boundary

- These CarServices paths can stop a protected application's process independently of Swipe, X, Close All, or Memory Cleaner.
- They do not remove a Recent Task by task ID in the inspected call paths. A task card may therefore remain or be updated separately from process termination.
- Blocking these operations globally would interfere with projection-source arbitration, ACC power-off, restart handling, and vendor wake-lock cleanup.
- The current Protection functional scope is preservation against Recents dismissal/cleanup and control of Recent Tasks card visibility. These vendor force-stop paths are outside that scope unless the functional specification is explicitly expanded to guarantee process survival against vendor services.
- Do not hook the shared `forceStopPackage()` helpers indiscriminately. Any future policy would need to identify the initiating service and reason, then apply a narrowly scoped exception policy.

### Research status

**Confirmed from source** — major CarServices force-stop helper definitions and the identified callers are traced. These paths are separate from the Recents `DeleteTaskDataEvent` and system `removeTask(taskId)` paths.

**Pending** — determine whether additional vendor services outside CarServices issue package force-stops or process kills relevant to the protected-app set. Runtime validation is required to establish which service paths are active on the target head unit and what happens to the corresponding Recent Task entries.
