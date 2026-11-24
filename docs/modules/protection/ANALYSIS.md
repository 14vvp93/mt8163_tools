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

**Confirmed** — the target firmware defines `com.android.systemui.SystemUIApplication` as its Application class and implements the no-argument `onCreate()` method. The main-process startup path is present in the decompiled target source. This provides a process-wide initialization point before Recents or Memory Cleaner UI is opened.

### Existing project integration

**Confirmed** — the project uses Legacy Xposed API 82 and the classic `assets/xposed_init` entry point. The current hook registry routes only the `android` package, so it does not yet install hooks in SystemUI.

**Implementation requirements:**
- Add a separate Protection installer for package `com.android.systemui`.
- Gate it on both `packageName == "com.android.systemui"` and the main process name `com.android.systemui`.
- Use the SystemUI `LoadPackageParam.classLoader`.
- Resolve exact methods with API 82; verify descriptors against the target build.
- Keep the existing SystemServer Media and Voltage initialization path unchanged.
- Fail closed for an unavailable Protection hook rather than breaking SystemUI startup.

**Implemented** — `HookRegistry` has a separate SystemUI route, gated by both package name and main process name. It does not alter the existing SystemServer Media/Voltage route. The Manifest scope includes `com.android.systemui`.

**Implemented** — `ProtectionHook` hooks the target firmware's `SystemUIApplication.onCreate()` and registers one process-wide configuration receiver after Application initialization. The receiver is independent of RecentsActivity and MemoryCleanerTile.

**Implemented** — the receiver validates the sending UID against the installed `com.mt8163.tools` package before accepting configuration-change broadcasts. The sender does not rely on the app-defined signature permissions used by the SystemServer configuration channels, because SystemUI is not assumed to hold those permissions.

**Implemented** — the SystemUI bootstrap installs the Recents and Memory Cleaner hooks described below. Automated build verification and target-device runtime validation remain pending.

### Protection configuration transport

**Implemented:**
- `ProtectionSettingsStore` mirrors the global module state and per-application settings to `protection_hook_config`.
- Application settings are keyed by package name and Android user ID.
- Configuration changes are sent explicitly to `com.android.systemui`.
- The SystemUI receiver validates the sender UID and reloads the configuration.
- `ProtectionTaskMatcher` publishes immutable configuration snapshots through a volatile reference. Its three predicates share the same package/user identity key.
- Keep and Hide are normalized as mutually exclusive; Hide takes precedence if both values are supplied.

**Pending:** confirm cross-process preference readability with the target LSPosed build and verify configuration updates after SystemUI restart and app process restart.

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

**Implemented** — the hook returns `false` only when the task identity is valid and Keep is enabled. All other cases retain the firmware result. The manual drag callback is not hooked.

### Card X

Sources:
- `recents/views/TaskViewHeader.java`
- `recents/views/TaskView.java`

**Confirmed** — the header's dismiss-button branch resolves its parent TaskView and calls `dismissTask()`.

**Implemented** — the hook suppresses only the dismiss-button branch for protected tasks. Application-info, move-task, and other click paths remain untouched.

### Keyboard dismissal

Source: `recents/views/TaskStackView.java`.

**Confirmed** — `DismissFocusedTaskViewEvent` is handled separately. The handler dismisses the focused TaskView and resets focus.

**Implemented** — the hook suppresses the event only for a protected focused task and calls the firmware's `resetFocusedTask(Task)`, preserving focus-frame cleanup.

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

**Implemented behavior:**

- The hook leaves the firmware's original Close all handler untouched when no Keep-protected task is present.
- If protected tasks exist, it suppresses the bulk `removeAllTasks(true)` path.
- It snapshots the stack and calls the existing per-task `dismissTask()` path for each attached unprotected TaskView. This reuses firmware Grid/non-Grid per-task animations and normal dismissal cleanup.
- Unattached unprotected tasks are removed through `TaskStack.removeTask(task, null, false, false)` and receive one `DeleteTaskDataEvent`.
- Protected tasks remain in the stack. The bulk all-dismissed announcement is not emitted by the suppressed handler.
- Focus is reset only when the focused task is being dismissed.

This deliberately uses the firmware's individual dismissal path rather than replacing its bulk animation trigger. It avoids calling `removeAllTasks(true)` while protected tasks remain.

**Pending device validation:**
- Grid and non-Grid with all tasks protected;
- mixed protected/unprotected tasks, including unattached TaskViews;
- per-task animation cancellation or view detachment;
- focus, layout, accessibility, and scrim when protected cards remain;
- exactly-once cleanup for unprotected tasks.

**Status** — selective Close all handling is implemented in source. Target-device validation remains pending.

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

**Implemented filtering boundaries:**
1. Filter the stack in `RecentsView.onReload(TaskStack, boolean)` before the view consumes it. The same stack object is then used by the caller's empty-state and navigation-bar scrim decisions.
2. Filter the stack in the `MultiWindowStateChangedEvent` constructor. Subscribers and deferred animation receive the filtered list.

**Implementation requirements:**
- Do not mutate a shared load plan.
- Preserve the order of visible tasks.
- Do not dispatch dismissal or cleanup events for hidden tasks.
- Do not call system task removal or force-stop.
- Keep hidden tasks available to Memory Cleaner.
- When Hide from Recents is disabled, the task should return on the next normal reload.

**Status** — both display-only filters are implemented. They mutate only the local display stack with `setTasks(filtered, false)`; they do not dispatch cleanup events or modify the shared task-load plan. Runtime validation remains pending.

## 8. Protect from MTK Memory Cleaner

Source: `qs/tiles/MemoryCleanerTile.java`.

**Confirmed** — `MemoryCleanerTile.clearRecentsTask()` builds and loads a Recents task list, then processes each task with a destructive sequence:

1. `RecentsTaskLoader.deleteTaskData(task, false)`;
2. `ActivityManagerWrapper.removeTask(taskId)`;
3. `ActivityManager.forceStopPackage(packageName)`.

This path does not depend on `DeleteTaskDataEvent`; intercepting that event alone cannot protect tasks from Memory Cleaner.

**Implemented** — the hook scopes a per-thread cleaner state to `clearRecentsTask()`. At `deleteTaskData(task, false)`, it resolves the package/user identity and records whether that task is protected. For protected tasks it suppresses cache deletion, the matching `ActivityManagerWrapper.removeTask(taskId)`, and the matching `ActivityManager.forceStopPackage(packageName)`. Unprotected tasks retain the original sequence.

**Implementation requirements:**
- Match package name and user ID defensively.
- Do not filter the shared task-load plan.
- Do not suppress Android system memory management.
- Preserve normal cleaner behavior for unprotected tasks.

**Status** — all three destructive operations are guarded in source. Runtime behavior and exact hook availability remain to be tested on the target device.

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

| Setting / action | Installed SystemUI method or boundary | Implemented behavior |
|---|---|---|
| Keep card — swipe | `TaskStackViewTouchHandler.canChildBeDismissed(View)` | Reject swipe dismissal for protected tasks; preserve original checks |
| Keep card — X | Dismiss-button branch in `TaskViewHeader.onClick(View)` | Suppress only the X action |
| Keep card — keyboard | `TaskStackView.onBusEvent(DismissFocusedTaskViewEvent)` | Skip protected dismissal; reset focus and focus frame |
| Keep card — Close all | `TaskStackView.onBusEvent(DismissAllTaskViewsEvent)` | Use per-task dismissals for attached views; selectively remove unattached tasks |
| Hide — initial display | `RecentsView.onReload(TaskStack, boolean)` | Filter local display stack before view consumes it |
| Hide — refresh | `MultiWindowStateChangedEvent(TaskStack)` constructor | Filter before subscribers and deferred animation consume it |
| Memory Cleaner | `clearRecentsTask()`, `deleteTaskData()`, `removeTask()`, `forceStopPackage()` | Suppress all three destructive operations for protected tasks |

Exact method descriptors and target class-loader resolution must be checked against the firmware build before installing hooks.

## 11. Implementation readiness and validation

### Implemented source hooks

- SystemUI main-process routing and the `SystemUIApplication.onCreate()` bootstrap hook.
- One process-wide configuration receiver with sender-UID validation.
- Configuration model, persistence mirror, and package/user matcher.
- Keep: swipe, card X, keyboard dismissal, and selective Close all.
- Hide: initial Recents stack and deferred multi-window refresh stack.
- Memory Cleaner: cache deletion, task removal, and package force-stop guards.
- MainApplication startup publication and publication when the Protection module enabled state changes.

### Ready at source-analysis level

- The three settings have separate firmware paths and boundaries.
- User dismissal origins are distinguished from package and launch-failure cleanup.
- Close all uses the firmware's per-task dismissal path for attached views and selective model cleanup for unattached tasks.
- Hide from Recents filters only the display stacks, avoiding shared-load-plan side effects.
- Memory Cleaner guards all three destructive operations in its per-task sequence.
- The target process is SystemUI and has a separate Xposed routing path.
- Task identity and null-safety requirements are established.

### Required before release

1. Complete automated build and module-test verification.
2. Verify configuration readability through XSharedPreferences on the target LSPosed build.
3. Verify each installed hook resolves on the target firmware and logs installation success.
4. Validate all three settings independently and their agreed mutual exclusion.
5. Test swipe, X, keyboard dismissal, and Close all in Grid and non-Grid layouts.
5. Test Memory Cleaner with protected and unprotected tasks.
6. Test Hide from Recents on initial display and refresh, including restoration after disabling the setting.
7. Test package changes, SystemUI restart, missing task identity, animation cancellation, and repeated user actions.
8. Confirm that protected tasks never reach the destructive cleanup sequence through the protected user action.

Protection hooks are implemented in source but have not yet been runtime-validated on the target head unit.

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
