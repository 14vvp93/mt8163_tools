# Protection Firmware Analysis

## 1. Purpose and scope

This document contains only firmware findings required to design and validate the Protection module.

- Project: MT8163 Tools
- Target: MT8163 head unit, Android 9 (API 28)
- Hook framework: LSPosed, Legacy Xposed API 82
- Firmware source: `14vvp93/public_worker`
- Decompiled SystemUI source: `artifacts/MtkSystemUI/jadx/sources/com/android/systemui/`

The user-visible behavior is defined in [README.md](README.md). This document records implementation-relevant firmware behavior and unresolved validation.

Evidence labels:
- **Confirmed** — directly observed in the inspected target source or target-firmware bytecode.
- **Candidate** — suitable interception/design point; not implemented or runtime-validated.
- **Pending** — requires target-device validation or additional evidence.

## 2. Protection behavior boundaries

Protection has three independent per-application settings:

1. **Keep card in Recents** — prevent user-initiated dismissal through swipe, card X, keyboard dismissal, and Close all.
2. **Hide from Recents** — omit the card from the visible Recents UI without deleting the system task.
3. **Protect from Memory Cleaner** — skip the application's task before the MTK cleaner performs destructive operations.

Persistent application matching must use validated package name and Android user ID. A task ID identifies a task instance and must not be used as the persistent application key.

Protection must not:
- terminate or force-stop an application merely to hide or retain its card;
- change Android's normal process lifecycle or global memory management;
- block package uninstall/update cleanup or failed-launch cleanup;
- alter framework-level automatic Recents retention policy;
- change normal firmware behavior for unprotected tasks.

## 3. Runtime and Xposed integration

### Target process

**Confirmed** — the audited Recents and Memory Cleaner classes run in the main `com.android.systemui` process:

- `RecentsActivity`
- Recents views and task handlers
- `MemoryCleanerTile`

They are not targets in the `android` SystemServer process.

### Existing project integration

**Confirmed** — the project uses Legacy Xposed API 82 and the classic `assets/xposed_init` entry point. The current hook registry routes only the `android` package, so it does not yet install hooks in SystemUI.

**Implementation requirements:**
- Add a separate Protection installer for package `com.android.systemui`.
- Gate it on both `packageName == "com.android.systemui"` and the main process name `com.android.systemui`.
- Use the SystemUI `LoadPackageParam.classLoader`.
- Resolve exact methods with API 82; verify descriptors against the target build.
- Keep the existing SystemServer Media and Voltage initialization path unchanged.
- Fail closed for an unavailable Protection hook rather than breaking SystemUI startup.

**Status** — process boundary and API-level integration are source-verified. Protection routing and hooks are not implemented.

## 4. Task identity and null safety

Sources:
- `shared/recents/model/Task.java`
- `shared/recents/model/RecentsTaskLoadPlan.java`

**Confirmed:**
- `Task.TaskKey` contains task ID, base Intent, source component, user ID, and windowing mode.
- `TaskKey.equals()` and `hashCode()` use task ID, windowing mode, and user ID only.
- `TaskStack.setTasks()` can reuse a Task object for an equal key and replace its key through `copyFrom()`.
- `TaskKey.getPackageName()` dereferences `baseIntent`; it can fail when the Intent is null.
- The base Intent component can be null. The fallback Intent package can also be null or empty.
- `sourceComponent`, `topActivity`, and the base Intent component are distinct fields and are not interchangeable identity guarantees.

**Implementation requirements:**
- Resolve package identity defensively from available task data.
- Validate every nullable field before dereferencing it.
- Match both package name and user ID.
- If identity cannot be established, do not guess. Preserve the original firmware behavior and do not claim the task is protected.
- Never persist settings by task ID.

## 5. Keep card in Recents — dismissal paths

### Swipe

Source: `recents/views/TaskStackViewTouchHandler.java` and the firmware `SwipeHelper`.

**Confirmed** — `SwipeHelper` asks the Recents callback `canChildBeDismissed(View)` when selecting a swipe candidate and again when deciding whether the completed gesture is a dismissal. The callback also checks normal stack and animation state.

**Candidate** — preserve the original result for ordinary tasks and return false for a protected task. Do not change the separate manual drag-and-drop path.

### Card X

Sources:
- `recents/views/TaskViewHeader.java`
- `recents/views/TaskView.java`

**Confirmed** — the header's dismiss-button branch resolves its parent TaskView and calls `dismissTask()`.

**Candidate** — gate only the dismiss-button branch for protected tasks. Preserve application-info, move-task, and other click behavior.

### Keyboard dismissal

Source: `recents/views/TaskStackView.java`.

**Confirmed** — `DismissFocusedTaskViewEvent` is handled separately. The handler dismisses the focused TaskView and resets focus.

**Candidate** — suppress dismissal for a protected focused task while preserving focus reset and focus-frame cleanup.

### Why a global dismissTask() hook is unsafe

**Confirmed** — `TaskView.dismissTask()` is also used by package/component cleanup and launch-failure cleanup. The dismissal event does not identify the initiating action.

**Implementation constraint** — do not globally block `dismissTask()` or filter its event consumer without distinguishing user dismissal from firmware cleanup.

## 6. Keep card in Recents — Close all

Sources:
- `recents/views/TaskStackView.java`
- `recents/views/TaskStackAnimationHelper.java`
- `shared/recents/model/TaskStack.java`
- `recents/RecentsActivity.java`

### Existing behavior

**Confirmed:**
- Close all is dispatched as `DismissAllTaskViewsEvent`.
- Grid and non-Grid layouts use different animation paths.
- Grid animations can call `onChildDismissed()` for attached TaskViews. This follows the individual dismissal path and can dispatch `DeleteTaskDataEvent`.
- The non-Grid animation path animates attached TaskViews but does not perform the same per-view removal callbacks.
- The bulk completion callback snapshots the original tasks, calls `TaskStack.removeAllTasks(true)`, and dispatches `DeleteTaskDataEvent` for the snapshot.
- Consequently, Grid tasks may receive duplicate cleanup through per-view callbacks and the original bulk snapshot.
- Tasks without an attached TaskView are not handled by the Grid animation callbacks.
- `removeAllTasks(true)` clears both stack lists and invokes `onStackTasksRemoved()`, which resets focus, returns views to the pool, clears ignored-task state, and dispatches `AllTaskViewsDismissedEvent`.
- The existing completion path announces that all Recents items were dismissed. That announcement is incorrect if protected cards remain.

### Safe selective-removal design

The following is a source-derived candidate algorithm, not an implemented hook:

1. At the start of the Close all handler, snapshot the stack and classify tasks by Protection settings and validated package/user identity.
2. Build a separate list of attached TaskViews for unprotected tasks. Do not mutate the live TaskView collection.
3. Pass only those unprotected views to the existing delete-all animation. Keep the original EventBus event and trigger lifecycle.
4. In the post-animation callback, take a **fresh** stack snapshot. Do not reuse the original snapshot for cleanup.
5. The fresh snapshot excludes Grid tasks already removed by their per-view callbacks. It still includes unbound Grid tasks and non-Grid tasks that remain in the model.
6. If no protected tasks remain, preserve the firmware's bulk-empty completion behavior and clean up each remaining unprotected task exactly once.
7. If protected tasks remain, remove only the remaining unprotected tasks with the existing immediate per-task TaskStack removal path, then dispatch one `DeleteTaskDataEvent` per removed task. Do not call `removeAllTasks(true)` while protected tasks remain.
8. Announce that all items were dismissed only when the resulting stack is actually empty. Preserve focus, layout, accessibility, metrics, and scrim behavior.

**Pending device validation:**
- Grid and non-Grid with all tasks protected;
- mixed protected/unprotected tasks, including unattached TaskViews;
- empty animation list;
- animation cancellation or view detachment and trigger completion;
- focus, layout, accessibility, and scrim when protected cards remain;
- exactly-once cleanup and force-stop behavior for unprotected tasks.

**Status** — source-level callback ordering and a selective-removal design are documented. Close all has not been implemented or validated on the head unit.

## 7. Hide from Recents — display-only filtering

Sources:
- `shared/recents/model/RecentsTaskLoadPlan.java`
- `recents/RecentsActivity.java`
- `recents/views/RecentsView.java`
- `recents/views/TaskStackView.java`
- `recents/views/TaskStackAnimationHelper.java`

**Confirmed:**
- The shared task-load plan is used by visible Recents, Memory Cleaner, and other Recents operations.
- Filtering the shared load plan would unintentionally hide tasks from Memory Cleaner and couple independent settings.
- Initial visible Recents display passes through `RecentsActivity.reloadStackView()`.
- Subsequent refreshes pass through `RecentsActivity.reloadTaskStack(boolean, boolean)`, which creates `MultiWindowStateChangedEvent`.
- The deferred stack animation consumes the incoming task list before calling `TaskStackView.setTasks()`. Filtering only inside `setTasks()` can leave animation calculations based on a different list.

**Candidate filtering boundaries:**
1. Filter the stack in `reloadStackView()` before passing it to `RecentsView.onReload()`. Use the filtered stack for empty-state and navigation-bar scrim decisions.
2. Filter the newly loaded stack in `reloadTaskStack(boolean, boolean)` before creating `MultiWindowStateChangedEvent`. All subscribers and deferred animation then receive the same list.

**Implementation requirements:**
- Do not mutate a shared load plan.
- Preserve the order of visible tasks.
- Do not dispatch dismissal or cleanup events for hidden tasks.
- Do not call system task removal or force-stop.
- Keep hidden tasks available to Memory Cleaner.
- When Hide from Recents is disabled, the task should return on the next normal reload.

**Status** — source-level display paths and shared-load-plan coupling are confirmed. The candidate filters are not implemented or runtime-validated.

## 8. Protect from MTK Memory Cleaner

Source: `qs/tiles/MemoryCleanerTile.java`.

**Confirmed** — `MemoryCleanerTile.clearRecentsTask()` builds and loads a Recents task list, then processes each task with a destructive sequence:

1. `RecentsTaskLoader.deleteTaskData(task, false)`;
2. `ActivityManagerWrapper.removeTask(taskId)`;
3. `ActivityManager.forceStopPackage(packageName)`.

This path does not depend on `DeleteTaskDataEvent`; intercepting that event alone cannot protect tasks from Memory Cleaner.

**Candidate** — apply the Protection predicate in the cleaner's per-task processing path before the first destructive operation. Skip all three operations for protected tasks and preserve the original sequence for all others.

**Implementation requirements:**
- Match package name and user ID defensively.
- Do not filter the shared task-load plan.
- Do not suppress Android system memory management.
- Preserve normal cleaner behavior for unprotected tasks.

**Status** — the cleaner's task acquisition and destructive sequence are source-traced. Runtime behavior remains to be tested.

## 9. System task removal and framework boundary

### Recents destructive cleanup

**Confirmed** — `RecentsActivity.onBusEvent(DeleteTaskDataEvent)`:
- clears loader queue/cache data through `deleteTaskData()`;
- requests system task removal through `ActivityManagerWrapper.removeTask(taskId)`;
- calls `ActivityManager.forceStopPackage(packageName)`.

`deleteTaskData()` alone is cache/load-queue cleanup; it does not remove the system task or force-stop the package.

**Implementation constraint** — protected tasks must be excluded before the dismissal path reaches cache cleanup, system task removal, or force-stop.

### Automatic framework trimming

Target `services.vdex` and `boot-framework.vdex` were inspected through the repository's GitHub Actions artifact.

**Confirmed in target bytecode:**
- The framework has a global Recent Tasks count limit initialized to 36 on low-RAM devices or 48 otherwise. Runtime branch selection and later overrides are not yet known.
- `RecentTasks.trimInactiveRecentTasks()` has a global count-limit pass and a separate policy-based pass.
- Trim notifications can reach `ActivityStackSupervisor.removeTaskByIdLocked()` and finish task activities.
- The inspected trim path does not establish that the application process is always killed; that outcome is not inferred.

**Protection boundary** — do not hook or suppress `RecentTasks.remove()`, `removeTaskByIdLocked()`, `finishActivityLocked()`, global task limits, or task persistence. These are framework lifecycle policies, not user-initiated Recents dismissal. Protection does not promise indefinite retention against automatic framework trimming.

**Evidence limitation** — these framework findings are decoded from CompactDEX because JADX did not reconstruct Java source for the inspected VDEX files.

## 10. Candidate hook surface

All entries below are candidates, not installed hooks.

| Setting / action | Candidate SystemUI method or boundary | Required behavior |
|---|---|---|
| Keep card — swipe | `TaskStackViewTouchHandler.canChildBeDismissed(View)` | Reject swipe dismissal for protected tasks; preserve original checks |
| Keep card — X | Dismiss-button branch in `TaskViewHeader.onClick(View)` | Suppress only the X action |
| Keep card — keyboard | `TaskStackView.onBusEvent(DismissFocusedTaskViewEvent)` | Skip protected dismissal; preserve focus cleanup |
| Keep card — Close all | `TaskStackView.onBusEvent(DismissAllTaskViewsEvent)` | Filter animation and post-animation cleanup consistently |
| Hide — initial display | `RecentsActivity.reloadStackView()` | Filter before visible UI consumes the stack |
| Hide — refresh | `RecentsActivity.reloadTaskStack(boolean, boolean)` | Filter before event construction |
| Memory Cleaner | Per-task processing in `MemoryCleanerTile.clearRecentsTask()` | Skip before cache cleanup, task removal, and force-stop |

Exact method descriptors and target class-loader resolution must be checked against the firmware build before installing hooks.

## 11. Implementation readiness and validation

### Ready at source-analysis level

- The three settings have separate firmware paths and boundaries.
- User dismissal origins are distinguished from package and launch-failure cleanup.
- Close all has a source-derived selective-removal design for Grid and non-Grid layouts.
- Hide from Recents has separate display entry points that avoid shared-load-plan side effects.
- Memory Cleaner has a distinct destructive path and candidate pre-operation filter.
- The target process is SystemUI; the current project registry does not yet route Protection there.
- Task identity and null-safety requirements are established.

### Required before release

1. Implement the SystemUI Protection process routing and hook installer using Legacy Xposed API 82.
2. Verify exact target method descriptors and class-loader visibility.
3. Validate all three settings independently and their agreed mutual exclusion.
4. Test swipe, X, keyboard dismissal, and Close all in Grid and non-Grid layouts.
5. Test Memory Cleaner with protected and unprotected tasks.
6. Test Hide from Recents on initial display and refresh, including restoration after disabling the setting.
7. Test package changes, SystemUI restart, missing task identity, animation cancellation, and repeated user actions.
8. Confirm that protected tasks never reach the destructive cleanup sequence through the protected user action.

No Protection hooks have been implemented and no runtime validation has been completed on the target head unit.

## 12. Primary firmware source files

The following decompiled SystemUI files are the primary references for the findings above:

- `recents/views/TaskStackViewTouchHandler.java`
- `recents/views/TaskStackAnimationHelper.java`
- `recents/views/TaskStackView.java`
- `recents/views/TaskView.java`
- `recents/views/TaskViewHeader.java`
- `recents/views/RecentsView.java`
- `recents/RecentsActivity.java`
- `shared/recents/model/Task.java`
- `shared/recents/model/TaskStack.java`
- `shared/recents/model/RecentsTaskLoadPlan.java`
- `shared/recents/model/RecentsTaskLoader.java`
- `shared/system/ActivityManagerWrapper.java`
- `qs/tiles/MemoryCleanerTile.java`

Target framework bytecode references:
- `services.vdex`: `com.android.server.am.RecentTasks`, `ActivityStackSupervisor`, `TaskRecord`, and related task persistence classes.
- `boot-framework.vdex`: `android.app.ActivityManager`.

**Final research status** — broad firmware reverse engineering is complete for the module's current scope. Further work should be limited to resolving implementation-specific method details and validating the implementation on the target head unit; unrelated Android framework internals are out of scope.
