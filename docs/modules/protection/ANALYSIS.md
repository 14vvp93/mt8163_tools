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

## 37. AutoBtClient and BluetoothService background-process kill helpers

Sources:

- `artifacts/AutoBtClient/jadx/sources/com/hcn/common/app/HProcessUtils.java`
- `artifacts/BluetoothService/jadx/sources/com/hcn/common/app/HProcessUtils.java`

**Confirmed** — both APKs contain equivalent `HProcessUtils` implementations with:

- `killAllBackgroundProcesses()`: enumerates running app processes and calls `ActivityManager.killBackgroundProcesses(packageName)` for each package found.
- `killBackgroundProcesses(packageName)`: checks running process package lists, requests background-process termination for the matching package, then checks whether that package remains in the running-process list.

**Confirmed** — repository-wide source search found the method definitions but no call sites for either helper in the inspected JADX source trees. Their presence alone does not prove that these methods are invoked at runtime; they may be unused, called reflectively, or reached from code outside the extracted source set.

**Scope distinction** — `killBackgroundProcesses()` targets background process cleanup and does not directly remove a Recent Task or dispatch `DeleteTaskDataEvent`. Do not classify it as a Recents card-deletion path without runtime evidence.

**Research status** — helper definitions are recorded. Their runtime callers and actual effect on protected applications remain unverified.

## 38. Direct process termination audit outside Recents

Sources:

- `artifacts/CarServises/jadx/boot-carservices_classes/sources/android/carstatus/CarStatusService.java`
- `artifacts/CarServises/jadx/boot-carservices_classes/sources/android/utilities/Util.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/keyguard/WorkLockActivityController.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/shared/system/ActivityManagerWrapper.java`
- `artifacts/AutoBtClient/jadx/sources/com/hcn/common/app/HProcessUtils.java`
- `artifacts/BluetoothService/jadx/sources/com/hcn/common/app/HProcessUtils.java`
- `artifacts/AutoBtClient/jadx/sources/com/hcn/common/app/HAppUtils.java`
- `artifacts/BluetoothService/jadx/sources/com/hcn/bluetooth/CrashHandler.java`
- `artifacts/AutoBtClient/jadx/sources/com/autochips/bluetooth/CrashHandler.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/SysuiRestartReceiver.java`

### Direct process kill in CarStatusService

**Confirmed** — `CarStatusService.checkWakeLock(clean)` parses the PowerManager wake-lock list. When `clean == true`, entries containing a PID are handled as follows:

1. If the PID differs from `Process.myPid()`, the service calls `Process.killProcess(pid)` directly.
2. If the PID is the CarStatusService process itself, it calls `findAndkillbyUid()` instead.
3. Entries without a parsed PID are also passed to `findAndkillbyUid()`.

**Confirmed** — `findAndkillbyUid()` extracts a WorkSource UID greater than 10000, resolves its package name, and calls the CarServices `Util.forceStopPackage(packageName)` helper.

**Confirmed** — the check is entered from the ACC-off Handler path after the wake-lock check counter advances beyond 15; another check is scheduled 5 seconds later while ACC remains off. The same method also checks the configured suspend timeout and may request a reboot through `sys.powerctl`.

**Scope implication** — this vendor power-management cleanup can terminate a process by PID without calling `forceStopPackage()` or removing a Recent Task. It is independent of Recents gestures and may affect any process represented by a wake-lock entry when cleanup is enabled. The exact target-device process selection and timing require runtime validation.

### Other Process.killProcess() matches

**Confirmed** — the remaining inspected matches are self-termination paths:

- `AutoBtClient` and `BluetoothService` `HAppUtils.relaunchApp(true)` launch the same application's launcher activity, then kill the current process and exit.
- Their crash handlers kill their own process before exiting.
- SystemUI `SysuiRestartReceiver` kills the SystemUI process itself after handling its restart broadcast.

No inspected call site uses these helpers to kill an arbitrary protected application's PID.

### Consolidated API search results

| API / operation | Confirmed source paths | Meaning for Protection |
|---|---|---|
| `IActivityManager.removeTask(taskId)` | `ActivityManagerWrapper.removeTask()`; `WorkLockActivityController.startWorkChallengeInTask()` | System Recent Task removal. WorkLock is a separate work-profile failure-cleanup path. |
| `TaskStack.removeTask()` | Recents dismissal, package-change cleanup, docking/model paths | In-memory Recents model mutation; not itself a system task-removal call. |
| `ActivityManager.forceStopPackage(packageName)` | Recents DeleteTaskDataEvent consumer; Memory Cleaner | Destructive Recents cleanup; also force-stops the package. |
| Vendor `Util.forceStopPackage()` / `SourceService.forceStopPackage()` | CarServices source arbitration, ACC/restart and wake-lock cleanup | Package termination outside Recents; current Protection scope does not block it. |
| `ActivityManager.killBackgroundProcesses(packageName)` | AutoBtClient and BluetoothService `HProcessUtils` helper definitions | Helper definitions found, but no call sites found in the inspected JADX source trees. |
| `Process.killProcess(pid)` | CarStatusService wake-lock cleanup; self-restart/crash paths | CarStatusService can kill a PID from the wake-lock list; other matches kill only their own process. |

**Confirmed** — repository-wide source search in the extracted firmware tree found no additional `removeTaskById` or `removeRecentTask` method/call pattern. Search results for `removeTask(` resolve to the two system-level paths above, Recents model methods, the loader queue's internal removal method, and duplicate JADX/smali representations.

**Audit limitation** — this is a source-tree search, not proof that no hidden/native/reflection-based process termination exists. Native binaries, dynamically loaded code, Binder calls through unindexed wrappers, and runtime-only behavior are outside this static result.

### Protection boundary

- Do not globally block `Process.killProcess()`, `forceStopPackage()`, or `killBackgroundProcesses()` as part of the current Recents-focused Protection behavior.
- The CarStatusService PID kill is a distinct vendor power-management action and must remain outside the current scope unless the functional requirements are explicitly expanded to process survival.
- WorkLock task removal is a work-profile credential failure path; whether it should be preserved is a separate policy decision.
- The AutoBtClient/BluetoothService helper definitions are not sufficient evidence to justify hooks without a confirmed caller.
- For the current scope, prioritize the Recents dismissal/Close All event path and the Memory Cleaner loop. Keep unrelated system and vendor lifecycle operations intact.

**Research status** — direct task-removal and process-termination APIs in the inspected JADX trees are classified. Runtime validation remains necessary for CarStatusService wake-lock cleanup, work-profile challenge failure, and vendor process termination behavior on the target Android 9 head unit.


## 33. PackageMonitor lifecycle and package-change cleanup

Sources:

- `recents/RecentsActivity.java`
- `recents/views/TaskStackView.java`
- `shared/recents/model/TaskStack.java`
- `shared/recents/model/RecentsTaskLoader.java`

### PackageMonitor registration and callbacks

**Confirmed** — `RecentsActivity.onCreate()` registers its `PackageMonitor` with:

- the Activity context;
- `Looper.getMainLooper()`;
- `UserHandle.ALL`;
- `externalStorage = true`.

**Confirmed** — `RecentsActivity.onDestroy()` unregisters the monitor.

The firmware overrides three callbacks:

- `onPackageRemoved(packageName, uid)`;
- `onPackageChanged(packageName, uid, components)`;
- `onPackageModified(packageName)`.

All three call `RecentsActivity.onPackageChanged(packageName, getChangingUserId())`. The `onPackageChanged` callback returns `true`.

**Confirmed** — no explicit `onPackageAdded()` or `onPackageUpdateFinished()` override was found in this `RecentsActivity` monitor. Do not infer that update completion is separately handled by this class. The PackageMonitor superclass may still perform its own broadcast/state bookkeeping; that behavior was not established from this class's overrides.

### Package-change processing

**Confirmed** — `RecentsActivity.onPackageChanged(packageName, userId)` performs two operations in order:

1. `RecentsTaskLoader.onPackageChanged(packageName)` removes matching package entries from the ActivityInfo cache.
2. It sends `PackagesChangedEvent(packageName, userId)` through EventBus.

**Confirmed** — `TaskStackView.onBusEvent(PackagesChangedEvent)` calls `TaskStack.computeComponentsRemoved(packageName, userId)`, then iterates the current stack from the end.

**Confirmed** — `TaskStack.computeComponentsRemoved()` considers only task keys whose `userId` equals the event user ID and whose component package matches the changed package. For each distinct component, it queries `PackageManagerWrapper.getActivityInfo(component, userId)`:

- non-null ActivityInfo: component remains available;
- null ActivityInfo: component is classified as removed.

**Confirmed** — for each classified component, TaskStackView behaves differently depending on view attachment:

- attached TaskView: calls `TaskView.dismissTask()`, which enters the normal dismissal/data-deletion event path;
- no attached TaskView: removes the task from the in-memory stack immediately with `AnimationProps.IMMEDIATE`, without this branch sending `DeleteTaskDataEvent`.

**Finding / risk** — package modification and package removal callbacks share the same cleanup path. There is no explicit replacement/update guard in the inspected callback overrides. If PackageManager temporarily cannot resolve an activity during package replacement, `computeComponentsRemoved()` can classify that component as removed. The exact callback timing and whether this transient condition occurs on the target firmware require runtime validation.

### Protection implications

- Package identity must remain package name plus user ID, as already specified in README; transient task IDs are unsuitable for persisted settings.
- A genuinely uninstalled package must not be kept as a permanent orphaned Recents card merely because its former package name remains in Protection settings.
- A package update must not cause a protected card to be discarded solely because ActivityInfo is temporarily unavailable during replacement. Validate the PackageMonitor replacement state and callback order before selecting a guard.
- The monitor's callback does not itself delete tasks; the destructive decision occurs later in `TaskStack.computeComponentsRemoved()` and its TaskStackView consumer.
- The ActivityInfo cache invalidation is package-wide in `RecentsTaskLoader`; it does not receive the user ID. Do not mistake that cache operation for the per-user task-removal decision, which is performed separately by TaskStack.

**Status** — registration scope, callback overrides, cache invalidation, per-user component lookup, and attached/detached TaskView branches are confirmed from source. Replacement timing and on-device behavior remain unverified.

## 34. Protection retention conditions and lifecycle boundary

This section interprets the agreed functional contract in `README.md` against the inspected firmware paths.

### What Keep card in Recents protects

**Confirmed from the project specification** — Keep card in Recents protects the Recents task/card against user dismissal through swipe, card X, and Close All. It does not promise to keep the application process alive or to override Android task/package lifecycle.

**Confirmed from source** — package-change cleanup is a separate path from user dismissal. It tests whether the task's component is still resolvable for the task's user.

### Retention conditions

A protected card is eligible to remain in Recents only while its task/component remains a valid system task and the package/component is still installed and resolvable for that user.

Protection must not convert these cases into permanent retained cards:

- package uninstall;
- component removal where the task's component no longer exists;
- system task removal performed by Android outside the Recents user-dismissal path.

A package update is not equivalent to uninstall. During replacement, retain the task if the package/component is restored and resolvable after the update; do not make a destructive decision from a transient lookup failure without validating replacement state.

**Important distinction** — the source's `computeComponentsRemoved()` checks component resolvability, not whether the package name is present in the Protection settings. Protection must be applied as an additional policy condition; it must not falsify PackageManager results or block genuine package lifecycle cleanup.

### Settings independence

- Keep card in Recents gates user-initiated Recents card dismissal.
- Hide from Recents controls visibility and is mutually exclusive with Keep card in Recents per README.
- Protect from Memory Cleaner gates only the task loop in MTK Memory Cleaner.
- None of these settings is a process-liveness guarantee.

**Status** — policy boundary is aligned with README and source cleanup behavior. Exact runtime handling of package replacement and external task removal still requires device testing.

## 35. Close All filtering: safe interception boundary

Source:

- `recents/views/TaskStackView.java`
- `recents/views/TaskStackAnimationHelper.java`
- `recents/views/RecentsViewTouchHandler.java`
- `recents/events/ui/DismissAllTaskViewsEvent.java`

### Existing handler sequence

**Confirmed** — `TaskStackView.onBusEvent(DismissAllTaskViewsEvent)` performs the following:

1. Copies every task from `mStack.getTasks()` into a local snapshot.
2. Calls `startDeleteAllTasksAnimation(getTaskViews(), useGridLayout(), trigger)` with every current TaskView.
3. Registers a post-animation callback.
4. The callback announces that all items were dismissed, calls `mStack.removeAllTasks(true)`, sends one `DeleteTaskDataEvent` for every task in the original snapshot, and logs the Close All metric.

The task snapshot, animation view list, final stack mutation, and deletion events are therefore coupled but independently materialized.

### Why a shallow filter is unsafe

- Filtering only the animation list leaves the final `removeAllTasks(true)` call able to remove protected tasks from the model.
- Filtering only the final stack mutation is too late: protected TaskViews have already been sent through the delete-all animation.
- Filtering only the bulk `DeleteTaskDataEvent` loop is also too late: the model has already been cleared, and the Grid animation path can independently dispatch per-task dismissal events.
- Filtering only `onChildDismissed()` does not protect the stack from the bulk `removeAllTasks(true)` callback.
- The Grid and non-Grid animation paths have different callback behavior; a shared assumption about per-view dismissal callbacks is invalid.

### Safe design requirements

A correct Close All implementation must make one consistent unprotected-task set and use it for all destructive stages:

1. Select unprotected tasks by package name plus user ID.
2. Select only the corresponding TaskViews for the delete animation.
3. Preserve protected tasks in the TaskStack model.
4. Ensure protected tasks never enter either the per-task dismissal path or the bulk data-deletion path.
5. Ensure every unprotected task is cleaned up exactly once, including the Grid layout's per-view callbacks.
6. Preserve EventBus trigger completion, animation completion, accessibility behavior, and the existing handling of non-protected tasks.

**Candidate interception boundary** — `TaskStackView.onBusEvent(DismissAllTaskViewsEvent)` is the earliest common consumer where the complete task stack and TaskViews are both available. It is the best place to coordinate the filtered operation, but a hook that merely edits one local list is insufficient. The operation must replace or coordinate the full handler sequence, including its post-animation callback and Grid per-view completion behavior.

**Not yet approved as implementation** — the exact hook method and callback replacement strategy still require a proof that Grid and non-Grid paths each produce exactly one cleanup per unprotected task and zero cleanup for protected tasks. Keep implementation deferred.

**Status** — source-level interception boundary and required invariants are identified. No runtime hook has been implemented or tested.


## 36. Package replacement semantics: AOSP PackageMonitor audit

Reference source: AOSP `PackageMonitor.java`, revision [c72fc67](https://android.googlesource.com/platform/frameworks/base/+/c72fc67/core/java/com/android/internal/content/PackageMonitor.java).

**Confirmed in the referenced PackageMonitor implementation**:

- `ACTION_PACKAGE_REMOVED` with `EXTRA_REPLACING=true` sets `PACKAGE_UPDATING` and calls `onPackageUpdateStarted()`. It does **not** call `onPackageRemoved()`.
- `ACTION_PACKAGE_REMOVED` without replacement sets `PACKAGE_PERMANENT_CHANGE` and calls `onPackageRemoved()`.
- `ACTION_PACKAGE_ADDED` with `EXTRA_REPLACING=true` calls `onPackageUpdateFinished()`, then `onPackageModified()`, and reports the package as appearing with `PACKAGE_UPDATING`.
- `ACTION_PACKAGE_CHANGED` calls `onPackageChanged()` and then `onPackageModified()`.
- `isPackageDisappearing(packageName)` returns the current change type while handling a disappearing-package callback. The referenced revision does not expose an `isReplacing()` method.

### Effect on RecentsActivity

The firmware overrides `onPackageRemoved()`, `onPackageChanged()`, and `onPackageModified()`, but not `onPackageUpdateStarted()` or `onPackageUpdateFinished()`.

Therefore:

1. The replacement-removal broadcast does not enter RecentsActivity's package cleanup through its `onPackageRemoved()` override.
2. The replacement-add broadcast reaches the inherited `onPackageUpdateFinished()` no-op, then RecentsActivity's `onPackageModified()` override.
3. That `onPackageModified()` callback invalidates the package ActivityInfo cache and dispatches `PackagesChangedEvent` after the package has been added again.

**Correction to Section 33 risk assessment** — the earlier concern that RecentsActivity necessarily performs the component lookup during the temporary replacement-removal window is not supported by this PackageMonitor dispatch sequence. The normal replacement path deliberately skips the overridden `onPackageRemoved()` callback and performs RecentsActivity's cleanup check on the add/finish side. A separate `ACTION_PACKAGE_CHANGED` event can still trigger the same cleanup path; whether it can occur during a transient unavailable interval is not established here.

**Implementation consequence** — do not add a speculative replacement delay or broad PackageManager-result override. First validate the actual target firmware's PackageMonitor implementation and package broadcast sequence. Do not rely on `isReplacing()` without confirming that the target class exposes it; the referenced source revision instead provides `isPackageDisappearing()` and the `PACKAGE_UPDATING` change type.

## 37. ReferenceCountedTrigger: exact Grid Close All callback order

Source: `recents/misc/ReferenceCountedTrigger.java` and `recents/events/EventBus.java`.

**Confirmed** — an `AnimatedEvent` increments its trigger before dispatch and decrements it after synchronous dispatch. Each Grid task animation:

1. increments the same trigger;
2. registers a last-decrement runnable that calls `TaskStackViewTouchHandler.onChildDismissed(taskView)`;
3. decrements the trigger when its 300 ms animation ends.

The Close All handler registers its own post-animation callback after registering the Grid animation callbacks.

**Confirmed** — `ReferenceCountedTrigger.decrement()` runs all registered last-decrement callbacks only when the shared count reaches zero, in registration order, then clears the callback list.

This establishes the Grid callback sequence:

1. All Grid card animations finish.
2. Each registered per-card callback calls `onChildDismissed()`.
3. Each call synchronously dispatches `TaskViewDismissedEvent`; TaskStackView removes that task from the model and sends `DeleteTaskDataEvent`.
4. The Close All post-animation callback then calls `removeAllTasks(true)` and sends `DeleteTaskDataEvent` for every task in the original snapshot.

**Confirmed duplicate dispatch** — tasks with an animated TaskView receive an individual `DeleteTaskDataEvent` and then another bulk `DeleteTaskDataEvent`. Tasks in the original stack snapshot without an animated TaskView receive only the bulk event. This is a source-level duplicate dispatch; the exact downstream impact of repeating loader cleanup, system task removal, and force-stop must be runtime-tested.

**Non-Grid path** — `startTaskStackDeleteAllTasksAnimation()` registers animation-completion decrements for each view but does not register `onChildDismissed()` callbacks. The post-animation callback therefore performs the only task-data deletion pass in this path.

### Consequences for a filtered Close All operation

- Grid filtering must be applied before animation callbacks are registered.
- The filtered task snapshot must be retained for final model and data cleanup.
- Do not combine Grid per-view dismissal callbacks with a second bulk deletion for the same task.
- Tasks without attached TaskViews still require cleanup; the implementation must account for them separately.
- The shared trigger must reach zero exactly once and must retain the event's normal post-animation completion semantics.
- Protected tasks must not be included in animation callbacks, model removal, or any data-deletion pass.

This narrows the required implementation, but does not approve a hook yet.

## 38. MTK Memory Cleaner: complete per-task destructive sequence

Source: `qs/tiles/MemoryCleanerTile.java`.

### Invocation lifecycle

**Confirmed** — `handleClick()` collapses Quick Settings panels and schedules message `what=1` after 1500 ms. The Handler then calls `clearRecentsTask()` and displays the completion Toast.

The delay is part of the firmware's click flow; it is not a repeating operation.

### Task acquisition and destructive operations

**Confirmed** — `clearRecentsTask()`:

1. Creates a `RecentsTaskLoadPlan`.
2. Calls `preloadTasks(loadPlan, -1)`.
3. Loads tasks using the current Recents launch-state visible-task and thumbnail counts.
4. Gets the resulting `TaskStack` and its task list.
5. For every task, performs all three operations in sequence:
   - `loader.deleteTaskData(task, false)`;
   - `ActivityManagerWrapper.removeTask(task.key.id)`;
   - `ActivityManager.forceStopPackage(packageName)`.

The Memory Cleaner loop does not dispatch the Recents `DeleteTaskDataEvent`; it directly invokes loader cleanup, system task removal, and package force-stop.

### Protection requirements

**Confirmed design consequence** — Protect from Memory Cleaner must skip the entire per-task operation for a protected package. Skipping only `forceStopPackage()` is insufficient because the preceding calls still delete Recents task data and remove the system task. Skipping only `removeTask()` is also insufficient because the loader data is deleted and the package is force-stopped.

**Candidate hook boundary** — `MemoryCleanerTile.clearRecentsTask()` is the narrowest inspected method containing the complete Memory Cleaner task loop. A future implementation should preserve the original load-plan behavior and apply the package protection predicate before any of the three destructive calls for each task. Do not globally hook `ActivityManagerWrapper.removeTask()`, `ActivityManager.forceStopPackage()`, or `RecentsTaskLoader.deleteTaskData()`; those APIs have unrelated callers and broader system effects.

**Important scope boundary** — `forceStopPackage(packageName)` is package-wide, while task removal uses a task ID. When a package is protected, skipping its task iteration also prevents this loop from force-stopping that package. This does not protect the package from force-stop operations initiated by other system or vendor paths.

**Status** — invocation delay, task acquisition, and all three destructive operations are confirmed. The proposed per-task filter is a design boundary only; no hook has been implemented or tested.


## 39. Hide from Recents: task-load pipeline and display-only boundary

Sources:

- `shared/recents/model/RecentsTaskLoadPlan.java`
- `shared/recents/model/RecentsTaskLoader.java`
- `recents/RecentsActivity.java`
- `recents/views/RecentsView.java`
- `recents/views/TaskStackView.java`
- `qs/tiles/MemoryCleanerTile.java`

### Shared task-load plan

**Confirmed** — `RecentsTaskLoadPlan.preloadPlan()` obtains the recent task list, resolves ActivityInfo, removes duplicate top components, applies the firmware resource list `recents_task_hidden_list`, and builds a `TaskStack`.

**Confirmed** — this load-plan path is not exclusive to the visible Recents UI. It is also used by:

- `MemoryCleanerTile.clearRecentsTask()`;
- RecentsActivity stack reload;
- RecentsImpl preloading and task-switching paths.

**Design conclusion** — adding the user-configured Hide from Recents predicate directly to `RecentsTaskLoadPlan.preloadPlan()` would also hide those tasks from the Memory Cleaner input and other non-display consumers. That would couple two settings that README requires to remain independent. Do not implement Hide from Recents as a global load-plan filter.

### Visible Recents entry points

**Confirmed** — the initial visible stack is passed from `RecentsActivity.reloadStackView()` to `RecentsView.onReload(stack, ...)`. `RecentsView.onReload()` updates the empty-state UI and passes the stack to `TaskStackView.setTasks()`.

**Confirmed** — a later stack refresh is built by `RecentsActivity.reloadTaskStack()` and dispatched as `MultiWindowStateChangedEvent`. Its consumers include:

- `RecentsView.onBusEvent()`, which updates empty-state visibility from the event stack;
- `TaskStackView.onBusEvent()`, which either calls `setTasks()` directly or starts the deferred stack animation.

**Confirmed** — the deferred animation path in `TaskStackAnimationHelper.startNewStackScrollAnimation()` reads the incoming stack's task list for layout calculations and then calls `TaskStackView.setTasks()`. Filtering only inside `TaskStackView.setTasks()` would leave the animation helper working with the unfiltered list and could produce index/layout inconsistencies.

### Candidate display-only filtering boundary

The filter must be applied to the stack before the UI consumes it, while leaving the source load plan and its other consumers unchanged.

Candidate entry points:

1. `RecentsActivity.reloadStackView()`: filter the stack before passing it to `RecentsView.onReload()`. This keeps the task-card list, empty-state decision, and initial layout based on the same filtered stack.
2. `RecentsActivity.reloadTaskStack()`: filter the newly loaded stack before constructing/dispatching `MultiWindowStateChangedEvent`. This keeps both RecentsView and TaskStackView, including deferred animation, on the same filtered stack.

The two entry points cover the inspected initial display and subsequent ActivityUnpinned/multi-window refresh paths. A final implementation must still audit every call site of these methods and every producer of `MultiWindowStateChangedEvent` in the exact target SystemUI build.

### Filtering invariants

- Filter by the agreed package identity, not task ID.
- Preserve original task order for all remaining tasks.
- Do not mutate the source load plan if another consumer may still use it.
- Ensure RecentsView empty-state/scrim decisions and TaskStackView layout receive the same filtered stack.
- Keep hidden tasks available to Memory Cleaner and non-display RecentsImpl operations.
- Hiding a card must not dispatch `DeleteTaskDataEvent`, call `removeTask()`, call `forceStopPackage()`, or otherwise mutate the system task list.
- When a hidden setting is disabled, the task must reappear on the next normal stack reload without requiring application relaunch.

**Status** — shared-load-plan coupling and visible UI refresh paths are confirmed. The two Activity methods are candidate hook boundaries, not approved implementation. Validate call-site coverage and UI behavior on the target device before coding.

## 40. RecentsActivity reload entry-point coverage

Source: `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/recents/RecentsActivity.java`.

### Initial Recents display

**Confirmed** — `reloadStackView()` is a private, no-argument method. The inspected call-site audit in the matching smali file found one invocation: `RecentsActivity.onStart()`.

The method consumes the optional preloaded `RecentsTaskLoadPlan`, loads task data, obtains its `TaskStack`, and passes that stack to `RecentsView.onReload(stack, mIsVisible)`. It then uses the same stack's task count to update the navigation-bar scrim.

**Candidate** — apply Hide from Recents filtering to the newly obtained stack before `RecentsView.onReload()`. The filtered stack must also be used for the navigation-bar scrim task-count decision. Do not modify the load plan itself.

### Subsequent stack refresh

**Confirmed** — `reloadTaskStack(boolean isInMultiWindowMode, boolean sendConfigChangedEvent)` is private and has the descriptor `(ZZ)V`. It creates a fresh load plan, preloads and loads tasks, obtains a new stack, optionally sends `ConfigurationChangedEvent`, and then sends `MultiWindowStateChangedEvent(isInMultiWindowMode, showDeferredAnimation, stack)`.

The matching smali call-site audit found two callers:

- `RecentsActivity.onMultiWindowModeChanged(...)`;
- `RecentsActivity.onBusEvent(ActivityUnpinnedEvent)`, when Recents is visible.

**Confirmed** — the inspected producer search found no other construction site for `MultiWindowStateChangedEvent` in the decompiled SystemUI source. Its subscribers include `RecentsView`, `TaskStackView`, and `SystemBarScrimViews`. The first two consume the event's stack; TaskStackView may pass it to deferred animation before reconciliation.

**Candidate** — filter the stack inside `reloadTaskStack(ZZ)V` after loading and before constructing the event. This ensures all stack-consuming UI subscribers receive the same filtered stack. Keep the original `inMultiWindow` and animation flags unchanged.

### Coverage boundary

The two methods cover the inspected initial display and subsequent Activity refresh paths. This is source-level call-site coverage for this RecentsActivity implementation, not proof that no vendor-specific alternate Recents UI exists elsewhere in the firmware.

## 41. Keep card in Recents: dismissal-origin audit

Sources:

- `recents/views/TaskStackViewTouchHandler.java`
- `recents/views/TaskViewHeader.java`
- `recents/views/TaskStackView.java`
- `recents/views/TaskView.java`
- `recents/views/RecentsView.java`

### User dismissal origins and candidate boundaries

**Confirmed** — swipe eligibility is evaluated by `TaskStackViewTouchHandler.canChildBeDismissed(View)`. It obtains the task from the TaskView and rejects views already in the swipe-animation map or tasks no longer present in the stack.

**Candidate** — preserve the original return value for ordinary tasks and return `false` for a task protected by Keep card in Recents. This is the narrow swipe eligibility boundary; do not alter drag-and-drop task handling without a separate audit.

**Confirmed** — the card X action is handled by `TaskViewHeader.onClick(View)`. When the clicked view is `mDismissButton`, it resolves the parent TaskView, calls `dismissTask()`, and records the dismissal-source metric.

**Candidate** — gate only the X-button branch for protected tasks. Preserve the other click branches, including application-info and move-task behavior.

**Confirmed** — keyboard dismissal enters through `TaskStackView.onBusEvent(DismissFocusedTaskViewEvent)`. The handler obtains the focused TaskView, calls `dismissTask()` when that view exists, and then resets focus.

**Candidate** — suppress only the protected task's dismissal while preserving the focus-reset and focus-frame cleanup behavior. A replacement or carefully coordinated interception of this handler must retain the reset sequence.

### Why TaskView.dismissTask() is not a global boundary

**Confirmed** — the method is called from more than user-initiated dismissal:

- `TaskViewHeader.onClick()`: card X action;
- `TaskStackView.onBusEvent(DismissFocusedTaskViewEvent)`: focused-task dismissal;
- `TaskStackView.onBusEvent(PackagesChangedEvent)`: package/component cleanup when a TaskView is attached;
- `RecentsView` launch-failure cleanup: a failed task launch with an attached TaskView.

Therefore, globally suppressing `TaskView.dismissTask()` for protected packages would also block firmware cleanup paths. The event `DismissTaskViewEvent` itself does not encode the dismissal origin, so filtering only its consumer cannot reliably distinguish user dismissal from system cleanup.

### Close All remains a separate coordinated operation

**Confirmed** — `RecentsViewTouchHandler.handleTouchEvent(MotionEvent)` sends `DismissAllTaskViewsEvent` when the stack action button is pressed and the release/cancel event is within its bounds. The existing common consumer remains `TaskStackView.onBusEvent(DismissAllTaskViewsEvent)`.

**Implementation constraint** — do not treat the action-button producer as the filtering boundary: the event is also consumed by other UI components for scrim behavior, and the task model, animation callbacks, and deletion events are coordinated in TaskStackView. Preserve the event dispatch and filter the complete TaskStackView operation as specified in Sections 35 and 37.

## 42. Candidate hook surface inventory

This inventory records source-level candidates only. It is not an implementation approval.

| Protection setting | Candidate method | Required behavior | Critical constraint |
|---|---|---|---|
| Keep card in Recents — swipe | `TaskStackViewTouchHandler.canChildBeDismissed(View)` | Reject swipe dismissal for protected task | Preserve original eligibility checks and normal drag behavior |
| Keep card in Recents — X | `TaskViewHeader.onClick(View)` | Suppress only protected task's dismiss-button branch | Preserve other click actions and normal metrics for unprotected tasks |
| Keep card in Recents — keyboard | `TaskStackView.onBusEvent(DismissFocusedTaskViewEvent)` | Skip protected task dismissal | Preserve focus-frame and focused-task reset behavior |
| Keep card in Recents — Close All | `TaskStackView.onBusEvent(DismissAllTaskViewsEvent)` | Animate/remove/clean only unprotected tasks | Coordinate Grid callbacks, non-Grid callbacks, model mutation, and exactly-once cleanup |
| Hide from Recents — initial display | `RecentsActivity.reloadStackView()` | Filter stack before UI receives it | Use filtered count for scrim; do not mutate shared load plan |
| Hide from Recents — refresh | `RecentsActivity.reloadTaskStack(boolean, boolean)` | Filter before event construction | All subscribers and deferred animation must receive the same filtered stack |
| Protect from Memory Cleaner | `MemoryCleanerTile.clearRecentsTask()` | Skip all destructive operations for protected package tasks | Predicate must run before loader cleanup, task removal, and force-stop |

### Common identity and safety requirements

- Resolve package identity and Android user ID from the task defensively.
- Do not classify a task as protected using a guessed package name when identity is incomplete.
- Preserve original firmware behavior for unprotected tasks.
- Keep Hide from Recents separate from Memory Cleaner task acquisition.
- Keep card retention separate from package lifecycle cleanup and failed-launch cleanup.
- No candidate above has been hooked or runtime-tested.

## 43. Research status after reload and dismissal-origin audit

### Newly confirmed

- `reloadStackView()` is called from `RecentsActivity.onStart()` in the inspected build.
- `reloadTaskStack(ZZ)V` has two callers: multi-window mode change and visible Recents handling of `ActivityUnpinnedEvent`.
- `RecentsActivity.reloadTaskStack()` is the only inspected producer of `MultiWindowStateChangedEvent`.
- `TaskView.dismissTask()` is shared by user dismissal and non-user cleanup paths; it is unsafe as a global Keep-card interception point.
- Swipe, X-button, keyboard dismissal, and Close All have distinct source-level boundaries.

### Remaining work

1. Prove a complete Close All algorithm that handles Grid and non-Grid layouts, including tasks without attached TaskViews, with exactly-once cleanup.
2. Inspect the complete manual-drag and swipe lifecycle before finalizing the swipe hook's side-effect guarantees.
3. Verify task package/user extraction for every candidate hook against null and unresolved-component cases.
4. Confirm Legacy Xposed API 82 method-hook compatibility and class-loader visibility for the selected SystemUI classes.
5. Validate package replacement, hidden-task restoration, animation behavior, and cleanup on the target MT8163 device.

## 44. SwipeHelper dismissal eligibility and drag lifecycle

Sources:

- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/SwipeHelper.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/recents/views/TaskStackViewTouchHandler.java`

### Swipe admission

**Confirmed** — `SwipeHelper.onInterceptTouchEvent()` obtains the candidate view through `Callback.getChildAtPosition()`. The Recents implementation returns a TaskView only when `canChildBeDismissed(view)` returns true.

**Confirmed** — after obtaining a candidate, SwipeHelper stores the result of `canChildBeDismissed()` in its per-gesture dismissal flag. During movement, a non-dismissable view is limited to a bounded resistance translation. On release, `isDismissGesture()` checks `canChildBeDismissed()` again before deciding whether to dismiss.

**Confirmed** — the Recents callback `TaskStackViewTouchHandler.canChildBeDismissed(View)` is therefore consulted at admission and again during gesture completion. Returning false for a protected task prevents it from being selected as a swipe-dismiss candidate through the normal touch path.

### Side effects and drag distinction

**Confirmed** — `TaskStackViewTouchHandler.onBeginDrag(View)` performs state changes for an accepted swipe: disables TaskView touch, changes clipping, adds the task to the ignored-task set, snapshots the task list and transforms, and recalculates stack layout.

**Confirmed** — the separate `onBeginManualDrag(TaskView)` entry point sets the active TaskView and swipe-animation map, then calls `onBeginDrag()`. This is a manual drag path used by Recents drag-and-drop behavior; it is not the same as swipe-dismiss admission.

**Implementation constraint** — use `canChildBeDismissed(View)` as the swipe-dismiss eligibility check, not `onBeginDrag()` or a broad touch-handler cancellation. Do not block manual drag-and-drop solely because Keep card in Recents is enabled unless a separate product requirement explicitly says that dragging is forbidden.

### Dismissal completion

**Confirmed** — after SwipeHelper accepts a dismiss gesture and its dismiss animation completes, it invokes `Callback.onChildDismissed(View)`. The Recents implementation restores clipping and touch, dispatches `TaskViewDismissedEvent`, updates swipe-specific focus/layout state, and records the dismissal metric.

**Implementation constraint** — do not rely on blocking `onChildDismissed()` as the primary protection boundary. By that point the dismiss animation has already been accepted and completed. Prevent protected tasks from entering the dismissal gesture through the eligibility callback.

**Status** — the swipe admission boundary is supported by both the Recents callback and its SwipeHelper caller. Runtime gesture validation remains required, especially for touch cancellation, manual drag-and-drop, and Grid layout.

## 45. Close All: verified Grid and non-Grid callback ordering

Sources:

- `recents/views/TaskStackView.java`
- `recents/views/TaskStackAnimationHelper.java`
- `shared/recents/model/TaskStack.java`
- `recents/misc/ReferenceCountedTrigger.java`
- `recents/events/EventBus.java`
- `recents/views/RecentsView.java`

### Trigger ordering

**Confirmed** — `DismissAllTaskViewsEvent` is an `EventBus.AnimatedEvent`. EventBus increments its trigger before dispatch and decrements it after dispatch. The TaskStackView handler registers the bulk callback after starting the delete animations.

**Confirmed** — each Grid delete animation increments the same trigger and registers its `onChildDismissed(TaskView)` runnable before the handler registers the bulk callback. When the final animation decrements the trigger to zero, `ReferenceCountedTrigger.flushLastDecrementRunnables()` executes callbacks in insertion order.

Consequently, all Grid per-TaskView callbacks run synchronously before the Close All bulk callback. Each callback sends `TaskViewDismissedEvent`; TaskStackView handles it synchronously, removes that task from `TaskStack`, and sends one `DeleteTaskDataEvent`.

### Grid path

**Confirmed** — `startDeleteAllTasksAnimation()` iterates the TaskViews passed to it and starts one Grid animation per view. Each animation's last-decrement runnable calls `TaskStackViewTouchHandler.onChildDismissed()`.

**Confirmed** — the subsequent bulk callback still operates on the original full Task list snapshot. It calls `TaskStack.removeAllTasks(true)` and dispatches `DeleteTaskDataEvent` for every task in that original snapshot.

**Confirmed** — therefore, for every task represented by an animated attached Grid TaskView, the normal Grid callback has already dispatched a DeleteTaskDataEvent before the bulk callback dispatches another one. Tasks without an attached TaskView are not removed by the animation callbacks and receive only the bulk DeleteTaskDataEvent.

### non-Grid path

**Confirmed** — `startTaskStackDeleteAllTasksAnimation()` only animates attached TaskViews and restores their clipping on animation end. It does not register `onChildDismissed()` callbacks.

The bulk callback is therefore the only task-model removal and DeleteTaskDataEvent pass in this path.

### TaskStack callback semantics

**Confirmed** — `TaskStack.removeTask(task, animation, fromDockGesture, dismissRecentsIfAllRemoved)` removes one task and invokes `onStackTaskRemoved()` only when the task is in the filtered stack list. The callback receives the supplied animation and final-removal flag.

**Confirmed** — `TaskStack.removeAllTasks(true)` clears the complete filtered stack list and raw task list, then invokes one `onStackTasksRemoved()` callback. TaskStackView's implementation resets focus, returns attached TaskViews to the pool, clears ignored tasks, and dispatches `AllTaskViewsDismissedEvent`.

**Confirmed** — TaskStackView's `onStackTaskRemoved()` returns the matching attached TaskView to the pool and removes the task from the ignored-task set. When an animation is supplied, it also updates the layout algorithm and relayouts TaskViews. If the stack becomes empty, the `dismissRecentsIfAllRemoved` argument determines whether it dispatches `AllTaskViewsDismissedEvent` or `ShowEmptyViewEvent`.

## 46. Close All: safe filtering and exactly-once cleanup design

This is a source-derived candidate algorithm, not an implemented or runtime-tested hook.

### Required snapshots

At entry to `TaskStackView.onBusEvent(DismissAllTaskViewsEvent)`:

1. Snapshot the current stack task list.
2. Classify the snapshot into protected and unprotected tasks using the Protection setting and the task's package/user identity.
3. Build a separate copy of attached TaskViews whose tasks are unprotected.
4. Never mutate the live list returned by `getTaskViews()`.

### Animation phase

- Pass only unprotected attached TaskViews to `startDeleteAllTasksAnimation()`.
- Keep the original `DismissAllTaskViewsEvent` dispatch and shared `ReferenceCountedTrigger`; do not bypass EventBus or manually flush/decrement the trigger.
- Grid callbacks then remove and clean each animated unprotected task through the existing `TaskViewDismissedEvent` path.
- Protected TaskViews receive no dismissal animation and no `TaskViewDismissedEvent`.
- non-Grid remains animation-only; its tasks stay in the stack until the post-animation callback.

### Post-animation cleanup phase

When the post-animation callback runs, take a fresh snapshot of the current stack and select only tasks that are still present and unprotected. This is the key to avoiding duplicate DeleteTaskDataEvent dispatch in Grid mode:

- Grid tasks already removed by their per-TaskView callbacks are absent from the fresh stack snapshot; do not dispatch DeleteTaskDataEvent for them again.
- Grid tasks without attached TaskViews remain in the stack and are included in this remaining-task pass.
- In non-Grid mode, no per-view removal occurred, so all remaining unprotected tasks are included.
- Protected tasks are excluded from both model removal and data cleanup.

If no protected tasks remain in the stack, the original bulk semantics can be retained for the remaining tasks: call `removeAllTasks(true)`, then dispatch DeleteTaskDataEvent once for each task in the fresh remaining-unprotected snapshot. This also preserves the existing `onStackTasksRemoved()` / `AllTaskViewsDismissedEvent` completion path, including the case where Grid animation callbacks have already emptied the stack.

If protected tasks remain, do not call `removeAllTasks(true)`: it would delete protected cards. Remove each remaining unprotected task with `removeTask(task, AnimationProps.IMMEDIATE, false, false)`, then dispatch one DeleteTaskDataEvent for that task. The explicit `false` final-removal flag prevents the per-task callback from reporting that all Recents tasks were dismissed while protected cards remain. The immediate animation prop makes TaskStackView's existing per-task callback recalculate and relayout the remaining cards.

### Accessibility and metrics

- Do not announce that *all* Recents items were dismissed while protected cards remain.
- Preserve the existing all-dismissed accessibility/event path when the resulting stack is empty.
- Preserve the Close All metric once per user action, not once per task.
- Preserve the scrim subscriber's handling of the original DismissAllTaskViewsEvent.

### Remaining validation

1. Verify `AnimationProps.IMMEDIATE` and per-task removal do not produce visual flicker or unwanted focus changes when several unbound tasks are removed in sequence.
2. Verify Grid callbacks complete before the post-animation callback on the target firmware, including cancelled/detached animations.
3. Verify an empty unprotected subset still completes the trigger and leaves protected cards untouched.
4. Verify user/task identity is stable between the initial and post-animation snapshots.
5. Runtime-test task cleanup, system task removal, force-stop, accessibility announcements, and repeated Close All actions on the MT8163 head unit.

**Conclusion** — filtering only the animation list is unsafe. The operation must filter both the animation TaskViews and the post-animation model/data cleanup. The fresh post-animation stack snapshot provides the source-level basis for exactly-once cleanup in Grid and non-Grid layouts.

## 47. Immediate layout reconciliation after partial Close All

Sources:

- `shared/recents/utilities/AnimationProps.java`
- `recents/views/TaskView.java`
- `recents/views/TaskStackView.java`
- `shared/recents/model/TaskStack.java`

**Confirmed** — `AnimationProps.IMMEDIATE` is constructed with a zero-millisecond duration and a linear interpolator. `isImmediate()` returns true when every configured property duration is zero.

**Confirmed** — `TaskStackView.updateTaskViewToTransform()` delegates to `TaskView.updateViewPropertiesToTaskTransform()`. For an immediate animation, TaskView applies the target transform directly, does not create/start an AnimatorSet, and invokes a supplied animation listener synchronously.

**Confirmed** — `TaskStack.removeTask(task, AnimationProps.IMMEDIATE, false, false)` removes one task from the stack and invokes `TaskStackView.onStackTaskRemoved()`. That callback returns the removed TaskView to the pool, removes the task from the ignored-task set, recalculates the layout algorithm, and relayouts remaining TaskViews with the supplied immediate animation.

**Conclusion** — the proposed per-task removal path has a firmware-native synchronous layout mechanism. It does not require a custom animation or a new timer. Runtime validation is still required for focus behavior and repeated relayout cost when multiple off-screen/unbound tasks remain.

## 48. Close All trigger completion and edge-case behavior

Sources:

- `recents/events/EventBus.java`
- `recents/misc/ReferenceCountedTrigger.java`
- `recents/views/TaskStackView.java`
- `recents/views/RecentsView.java`

### Empty animation list

**Confirmed** — EventBus calls `AnimatedEvent.onPreDispatch()` before invoking subscribers and `onPostDispatch()` after synchronous dispatch. These methods increment and decrement the event's trigger.

**Confirmed** — TaskStackView registers its post-animation callback even when the filtered TaskView animation list is empty. With no animation increments, EventBus's post-dispatch decrement reaches zero and flushes the callback synchronously.

**Implication** — an all-protected stack or a stack with no attached unprotected TaskViews does not leave the Close All event waiting for an animation that will never start. The post-animation branch must still be safe to execute immediately.

### Refined post-animation cases

The fresh stack snapshot must be partitioned again using the same protection policy:

1. **No tasks remain:** preserve the firmware's empty-stack completion behavior, including `removeAllTasks(true)` if required to dispatch `onStackTasksRemoved()` and `AllTaskViewsDismissedEvent`. Avoid sending duplicate DeleteTaskDataEvent for tasks already removed by Grid callbacks.
2. **Only protected tasks remain:** do not mutate the stack, do not dispatch DeleteTaskDataEvent, and do not announce that all Recents items were dismissed. The original Close All action metric may still be recorded once for the user action.
3. **Protected and unprotected tasks remain:** remove only remaining unprotected tasks individually with `removeTask(task, AnimationProps.IMMEDIATE, false, false)`, then dispatch one DeleteTaskDataEvent per such task. Protected cards remain in the stack.
4. **No protected tasks remain, but unprotected tasks remain:** retain the bulk `removeAllTasks(true)` completion path and dispatch DeleteTaskDataEvent once for each task in the fresh snapshot.

### Trigger and animation cancellation

**Confirmed** — the Grid delete animator decrements the shared trigger from its `onAnimationEnd()` listener. The reference-counted trigger flushes callbacks only when its count reaches zero. Its generic `decrement()` method does not deduplicate repeated calls; the Grid animator listener itself has no local `hasEnded` guard.

**Validation required** — verify on the target build that each Grid animator produces exactly one end callback under normal completion, cancellation, and view detachment. Do not manually decrement or flush the shared trigger from a hook; that can cause premature post-animation cleanup or a negative trigger count.

### Accessibility and scrim

**Confirmed** — the existing TaskStackView bulk callback always announces `accessibility_recents_all_items_dismissed`. That announcement is incorrect when protected cards remain and must be conditional on the resulting stack actually being empty.

**Confirmed** — `RecentsView.onBusEvent(DismissAllTaskViewsEvent)` independently fades the background scrim when there is no docked task. This subscriber still receives the original event even if TaskStackView filters protected tasks.

**Validation required** — inspect the resulting scrim and protected-card presentation when every task is protected or when protected cards remain. Do not suppress the original event solely to alter TaskStackView behavior; the event also drives the independent RecentsView scrim subscriber.

## 49. Task identity fields available to Protection predicates

Source: `shared/recents/model/Task.java`.

**Confirmed** — `Task.TaskKey` exposes:

- `userId`: the Android user ID associated with the task;
- `baseIntent`: the task's base Intent;
- `getPackageName()`: returns the base Intent component's package when a component exists, otherwise the Intent package.

**Null-safety constraint** — `TaskKey.getPackageName()` dereferences `baseIntent` without checking it for null. A Protection predicate must validate the task, key, and baseIntent before calling it. It must also handle a null component and an empty/unavailable package name without treating the task as protected by a guessed identity.

**Conclusion** — package identity and user ID are available from the task key. Whether the Protection preference is package-global or user-scoped remains a product-policy decision; do not infer that from the task key alone.

## 50. Close All algorithm status after edge-case audit

### Source-level behavior now established

- Grid attached unprotected tasks are removed by their animation callbacks before the post-animation callback.
- non-Grid bulk animations do not remove tasks individually; the post-animation callback owns their model/data cleanup.
- Unattached unprotected tasks remain in the stack after Grid animation and must be included in the fresh post-animation snapshot.
- The fresh snapshot avoids duplicate DeleteTaskDataEvent dispatch for Grid tasks already removed through TaskViewDismissedEvent.
- Partial removal can use the existing zero-duration TaskStack/TaskView layout path.
- Empty animation lists still execute the post-animation callback through the AnimatedEvent trigger lifecycle.
- The post-animation callback must distinguish an empty stack, protected-only remainder, and mixed protected/unprotected remainder.
- Package/user identity can be read from TaskKey, but all nullable fields must be checked before resolving package identity.

### Remaining device validation

1. Grid and non-Grid with all tasks protected.
2. Grid with a mix of protected/unprotected attached TaskViews and unbound tasks.
3. non-Grid with a mix of protected/unprotected tasks.
4. Grid animation cancellation and view detachment; verify exactly-once trigger decrement.
5. Accessibility announcements and scrim when protected cards remain.
6. Focus stability, TaskView pooling, and relayout after sequential immediate removals.
7. Confirm whether preference matching is package-global or user-scoped.

**Status** — the Close All algorithm is now specified at source level for the identified layouts and edge cases. It is not implemented and has not been runtime-tested on the target head unit.

## 51. Protection target process: SystemUI, not system_server

Sources:

- `artifacts/MtkSystemUI/apktool/AndroidManifest.xml`
- `recents/RecentsActivity.java`
- `qs/tiles/MemoryCleanerTile.java`
- Current MT8163 Tools `AndroidManifest.xml`, `XposedEntry.java`, and `HookRegistry.java`.

**Confirmed** — the firmware SystemUI APK package is `com.android.systemui`. Its application-level process is `com.android.systemui`.

**Confirmed** — `RecentsActivity` is declared without its own `android:process`, so it uses the application process. `MemoryCleanerTile` is a SystemUI QS tile class and likewise runs in the SystemUI application process.

**Confirmed** — the inspected SystemUI manifest also declares secondary processes such as `:screenshot`, `:tuner`, `:sweetsweetdesserts`, and `:fgservices`. These are not the target process for the Protection methods audited here.

**Confirmed** — the current MT8163 Tools module declares `xposedscope=android`. Its `XposedEntry` forwards load-package callbacks to `HookRegistry`, but `HookRegistry.handleLoadPackage()` returns immediately unless `param.packageName` equals `android`. Its current hook list contains MediaHook and VoltageHook, both targeting `android`.

**Conclusion** — the current package scope and runtime dispatch do not install Protection hooks in SystemUI. Adding a ProtectionHook class alone would not make it run.

### Required target routing

- Keep the existing `android` target for the current Media/Voltage hooks and SystemServer bootstrap.
- Add `com.android.systemui` as a separate target for Protection.
- Route SystemUI to its own hook set; do not call the SystemServer bootstrap from the SystemUI branch.
- Restrict Protection installation to `processName == "com.android.systemui"` so it does not attempt Recents/Memory Cleaner class lookup in unrelated SystemUI secondary processes.
- Keep a second package/process check inside the Protection hook installer. Scope metadata is a manager recommendation, not a substitute for runtime target validation.

## 52. Legacy Xposed API 82: target class-loader and hook method compatibility

References:

- `app/build.gradle`: `compileOnly 'de.robv.android.xposed:api:82'`
- `app/src/main/assets/xposed_init`: `com.mt8163.tools.xposed.XposedEntry`
- `app/src/main/java/com/mt8163/tools/xposed/XposedEntry.java`
- `app/src/main/java/com/mt8163/tools/xposed/HookRegistry.java`
- Legacy Xposed API reference: `IXposedHookLoadPackage`, `XC_LoadPackage.LoadPackageParam`, and `XposedHelpers.findAndHookMethod()`.

**Confirmed** — the project compiles against Legacy Xposed API 82 using `compileOnly`, and the classic `assets/xposed_init` entry names `XposedEntry`. `XposedEntry` implements `IXposedHookLoadPackage`.

**Confirmed** — `LoadPackageParam` exposes `packageName`, `processName`, and the target package's `classLoader`. The callback is invoked early in package loading, before `Application.onCreate()`.

**Confirmed** — API 82 provides `XposedHelpers.findAndHookMethod(String, ClassLoader, String, Object...)`. Its class loader resolves both the target class and any parameter classes. Exact parameter types are required; primitive parameters must use primitive class tokens such as `boolean.class`, not boxed `Boolean.class`.

**Confirmed** — `findAndHookMethod()` resolves a method declared or overridden in the named class. It does not search inherited implementations as a substitute for a missing declaration. The exact method must therefore be verified on the target firmware before installation.

### Loader rule for Protection

Use the `classLoader` from the `LoadPackageParam` delivered for `com.android.systemui`. Do not use the SystemServer callback's class loader and do not pass null: null selects the Xposed boot/system class loader, not the SystemUI application loader.

The SystemUI target classes are not compile-time dependencies of MT8163 Tools. Resolve them by their firmware class names through the SystemUI class loader and pass exact parameter types to the API 82 helper.

### Candidate method descriptors

These descriptors are source-derived from the inspected MT8163 SystemUI build. They are candidates for exact API 82 lookup, not proof of successful runtime hooking.

| Target class | Method | Descriptor | Visibility |
|---|---|---|---|
| `com.android.systemui.recents.views.TaskStackViewTouchHandler` | `canChildBeDismissed` | `(android.view.View)Z` | public |
| `com.android.systemui.recents.views.TaskViewHeader` | `onClick` | `(android.view.View)V` | public |
| `com.android.systemui.recents.views.TaskStackView` | `onBusEvent` | `(com.android.systemui.recents.events.ui.focus.DismissFocusedTaskViewEvent)V` | public |
| `com.android.systemui.recents.views.TaskStackView` | `onBusEvent` | `(com.android.systemui.recents.events.ui.DismissAllTaskViewsEvent)V` | public |
| `com.android.systemui.recents.RecentsActivity` | `reloadStackView` | `()V` | private |
| `com.android.systemui.recents.RecentsActivity` | `reloadTaskStack` | `(boolean, boolean)V` | private |
| `com.android.systemui.qs.tiles.MemoryCleanerTile` | `clearRecentsTask` | `()V` | private |

**Compatibility assessment** — all listed signatures can be expressed through the Legacy API 82 string-class-name/class-loader form of `findAndHookMethod()`. Private visibility is not, by itself, a blocker: XposedHelpers resolves the exact declared method and makes it accessible before hooking. Firmware signature drift, class-loader mismatch, or a method being inlined/removed remain runtime risks.

## 53. LSPosed scope declaration and SystemUI process selection

Reference: official LSPosed Module Scope documentation.

**Confirmed** — LSPosed's legacy `xposedscope` metadata accepts a semicolon-separated package list and preselects those packages in the manager. The configured scope remains user-controlled.

**Current state** — MT8163 Tools declares only `android`; therefore SystemUI is not in the module's recommended scope. The hook registry also explicitly rejects every package except `android`.

**Required declaration for Protection** — retain `android` and add `com.android.systemui` to the legacy scope list. The user must verify that `com.android.systemui` is actually enabled in the module's LSPosed scope; changing the manifest recommendation alone does not update an already configured scope.

**Process selection** — the Xposed callback exposes `processName`. For the inspected firmware, install the Protection hook set only when both package and process are `com.android.systemui`. This targets RecentsActivity and MemoryCleanerTile while avoiding unrelated secondary SystemUI processes.

**Security boundary** — `com.android.systemui` is a privileged system application process. Do not add broad package scopes or install the Protection hooks in `android`/SystemServer: the audited target classes are in SystemUI, and a SystemServer hook is unnecessary for these features.

## 54. Protection Xposed integration readiness

### Confirmed

- Legacy API 82 dependency and classic entry point are already configured.
- The entry point receives package name, process name, and target class loader.
- The current registry is restricted to `android`; it cannot currently dispatch a SystemUI Protection hook.
- The firmware's Recents and Memory Cleaner targets run in the main `com.android.systemui` process.
- All seven candidate hook descriptors can be represented with API 82 exact method lookup.
- The correct target loader is the SystemUI `LoadPackageParam.classLoader`.

### Required before implementation

1. Add `com.android.systemui` to the recommended legacy LSPosed scope and verify the configured scope on-device.
2. Extend package routing without changing the existing SystemServer Media/Voltage bootstrap path.
3. Add a separate Protection hook installer gated by both package and main process name.
4. Resolve every candidate method using the exact descriptor and SystemUI class loader; fail closed per hook if a method is absent.
5. Verify the SystemUI process receives the callback and each hook installs exactly once after process start.
6. Test restart/reload behavior after SystemUI process death and LSPosed scope changes.

**Status** — the API-level integration path and process boundary are source-verified. No source code or manifest changes were made; no Protection hooks have been installed or runtime-tested.

## 55. Package-change cleanup: attached versus unattached TaskViews

Sources:

- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/recents/RecentsActivity.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/shared/recents/model/RecentsTaskLoader.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/shared/recents/model/TaskStack.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/recents/views/TaskStackView.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/recents/views/TaskView.java`

### Package-change dispatch

**Confirmed** — the three RecentsActivity PackageMonitor callbacks (`onPackageRemoved()`, `onPackageChanged()`, and `onPackageModified()`) call the same `onPackageChanged(packageName, getChangingUserId())` method.

That method:

1. Calls `RecentsTaskLoader.onPackageChanged(packageName)`.
2. Sends `PackagesChangedEvent(packageName, userId)`.

**Confirmed** — the loader invalidates every cached ActivityInfo entry whose component package matches `packageName`. The cache invalidation itself is package-wide; the subsequent TaskStack component scan applies the event's `userId`.

### Component-removal decision

**Confirmed** — `TaskStack.computeComponentsRemoved(packageName, userId)` scans task keys and ignores tasks belonging to a different user ID. For matching package components, it calls `PackageManagerWrapper.getActivityInfo(component, userId)`:

- non-null ActivityInfo: the component is treated as existing;
- null ActivityInfo: the component is returned as removed.

**Confirmed** — `TaskStackView.onBusEvent(PackagesChangedEvent)` processes tasks whose component is in that removed-component set. Its cleanup then branches on whether a TaskView is currently attached.

### Attached TaskView path

When `getChildViewForTask(task)` returns a TaskView:

1. TaskStackView calls `TaskView.dismissTask()`.
2. TaskView dispatches `DismissTaskViewEvent` and registers a post-animation callback.
3. The callback dispatches `TaskViewDismissedEvent`.
4. TaskStackView removes the task from its in-memory stack and sends `DeleteTaskDataEvent`.
5. RecentsActivity handles that event by clearing loader data, requesting system task removal, and force-stopping the task package.

**Confirmed** — this path reaches the destructive Recents cleanup handler.

### Unattached TaskView path

When `getChildViewForTask(task)` returns null, TaskStackView directly calls:

```java
mStack.removeTask(task, AnimationProps.IMMEDIATE, false);
```

**Confirmed** — this branch removes the task from the in-memory Recents stack. It does not call `TaskView.dismissTask()` and does not dispatch `DeleteTaskDataEvent` in this handler. Consequently, this branch does not itself invoke RecentsActivity's `ActivityManagerWrapper.removeTask()` or `forceStopPackage()` sequence.

### Protection implications

- Package/component lifecycle cleanup is distinct from user-initiated Keep card in Recents behavior. Do not globally suppress `TaskView.dismissTask()` or `PackagesChangedEvent` for protected packages.
- The same package-change event can produce different cleanup side effects depending on whether the task currently has an attached TaskView.
- Removing a task from the in-memory Recents stack is not proof that the system Recent Task was removed.
- Do not use this package-cleanup path as the implementation boundary for Hide from Recents or Keep card in Recents.
- Preserve genuine package/component removal behavior; Protection must not fabricate ActivityInfo or override PackageManager results.

**Pending** — validate the attached/unattached behavior on the target head unit, including package uninstall, component disable/removal, and package modification. The source does not establish that an ActivityInfo lookup can be transiently null during every package-change event, so no temporary-absence recovery rule is inferred here.

**Status** — the source-level branch and its distinct downstream side effects are confirmed. No hooks or firmware code were changed.

## 56. External task removal and Recents stack reconciliation

Sources:

- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/shared/system/TaskStackChangeListener.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/shared/system/TaskStackChangeListeners.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/shared/system/ActivityManagerWrapper.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/recents/RecentsImpl.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/recents/RecentsActivity.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/shared/recents/model/TaskStack.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/recents/views/TaskStackView.java`
- `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/recents/views/RecentsView.java`

### System task-removal notification

**Confirmed** — the firmware's `TaskStackChangeListener.onTaskRemoved(int taskId)` callback carries only the task ID. It does not carry package name or user ID.

**Confirmed** — `TaskStackChangeListeners.onTaskRemoved(int taskId)` posts message 13 to its Handler. The Handler dispatches `onTaskRemoved(taskId)` to registered listeners.

**Confirmed** — `RecentsImpl.TaskStackListenerImpl` does not override `onTaskRemoved()`. Its inherited implementation is empty. The inspected RecentsActivity does not register a TaskStackChangeListener either.

**Conclusion** — there is no source evidence that RecentsActivity directly removes or protects a card in response to this callback. The callback alone is not a suitable package/user protection predicate.

### General task-stack-changed notification

**Confirmed** — `TaskStackChangeListeners.onTaskStackChanged()` first snapshots registered listeners and invokes `onTaskStackChangedBackground()` on them. It then removes any pending message 1 and posts a new message 1 to its Handler. This coalesces pending foreground stack-change notifications.

**Confirmed** — `RecentsImpl.TaskStackListenerImpl.onTaskStackChangedBackground()` can preload a fresh Recents task plan and load thumbnails for cache when Overview is not enabled, the current-user check passes, the svelte level is zero, and the window bounds are non-empty.

**Confirmed** — this background path does not call `RecentsActivity.reloadTaskStack()`, dispatch `MultiWindowStateChangedEvent`, or mutate the active RecentsActivity TaskStack in the inspected method.

**Conclusion** — the observed TaskStackListener background path is cache/preload work, not the active Recents card reconciliation boundary.

### Active Recents reload paths

**Confirmed** — `RecentsActivity.onStart()` calls `reloadStackView()`. That method obtains or creates a `RecentsTaskLoadPlan`, preloads tasks when necessary, loads task data, and passes the resulting stack to `RecentsView.onReload()`. The same stack's task count is used for the navigation-bar scrim.

**Confirmed** — `reloadTaskStack(boolean, boolean)` builds a fresh load plan and sends `MultiWindowStateChangedEvent` with the resulting stack. Its inspected callers are multi-window mode changes and the visible ActivityUnpinnedEvent handler.

**Confirmed** — `RecentsView.onBusEvent(MultiWindowStateChangedEvent)` calls `updateStack(event.stack, false)`. This updates empty-state visibility but does not itself call `TaskStackView.setTasks()`.

**Confirmed** — `TaskStackView.setTasks(stack, allowNotifyStackChanges)` delegates to the internal TaskStack with notifications enabled only when the caller allows them and the layout algorithm is already initialized. When notifications are enabled, TaskStack computes added and removed task keys and calls the corresponding TaskStack callbacks.

### TaskStack replacement semantics

**Confirmed** — `TaskStack.setTasks(List<Task>, notifyStackChanges)` compares old and new task-key maps. When notifications are enabled, tasks missing from the new list are removed from the model and passed to `onStackTaskRemoved()` with `AnimationProps.IMMEDIATE`; new keys are passed to `onStackTaskAdded()`; the operation then calls `onStackTasksUpdated()`.

**Confirmed** — when notifications are disabled, the method replaces the raw and filtered task lists without generating the removed/added callbacks. Existing task objects are reused by key and updated with `copyFrom()`.

**Important distinction** — a stack refresh can therefore replace the visible model without invoking per-task removal callbacks, depending on the notification flag and layout initialization state. Do not assume every externally missing task passes through `TaskView.dismissTask()` or `DeleteTaskDataEvent`.

### Protection implications

- `onTaskRemoved(taskId)` is a notification, not a veto point; returning from it cannot preserve a system task already removed by ActivityManager.
- Do not infer package identity from task ID alone or persist task-ID protection as a substitute for package/user identity.
- The observed RecentsImpl background listener refreshes cache data; it is not evidence of active-card retention.
- RecentsActivity's next `onStart()` reload obtains a new task plan. A task absent from that plan cannot be retained merely by filtering the old visible stack.
- Stack replacement may bypass per-task callbacks when notifications are disabled. Any future Protection filtering must operate on the incoming stack before it becomes the active UI model, not depend exclusively on removal callbacks.
- Keep display filtering separate from system task removal. A task omitted from the displayed stack must not be removed from ActivityManager or have loader data deleted solely because it is hidden.
- Do not globally intercept `ActivityManagerWrapper.removeTask()` or `TaskStackChangeListener.onTaskRemoved()`; both are broader than a user-configured Recents policy.

**Pending validation**:

1. Confirm the exact firmware's ActivityManager callback ordering for `onTaskRemoved()` and `onTaskStackChanged()`.
2. Verify whether the active Recents UI can remain visible while an external task removal occurs, and which event causes its next reload on this firmware.
3. Test a protected task removed externally by Android, then reopen Recents and confirm the expected policy: Protection must not resurrect a task that the system no longer reports.
4. Test task-stack replacement with notification enabled and disabled, including attached and unattached TaskViews.
5. Confirm whether the target firmware has any vendor-specific listener or refresh path not represented in the inspected SystemUI source.

**Status** — callback payloads, listener dispatch, RecentsImpl background behavior, Activity reload paths, and TaskStack replacement semantics are source-verified. Callback ordering and active-UI behavior under external removal remain device-validation items. No hooks or firmware code were changed.
