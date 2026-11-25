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

Protection has three configurable per-application settings:

1. **Protect from Memory Cleaner** — skip the application's task before the MTK cleaner performs destructive operations.
2. **Hide application card in Recents** — omit the card from the visible Recents UI without deleting the system task.
3. **Allow closing card via X** — permit the X-button dismissal exception.

Keep is implicit: an application entry in the Protection list is itself the Keep state. There is no separate Keep boolean or persisted key. While listed and while the module is enabled, the task is protected from swipe dismissal, keyboard dismissal, and Close all. The X button is blocked unless **Allow closing card via X** is enabled. Hide and Memory Cleaner protection are independent settings.

Persistent application matching uses the validated package name. The target head unit has one Android user. A task ID identifies a temporary task instance and must never be used as the persistent application key.

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

**Confirmed** — the project uses Legacy Xposed API 82 and the classic `assets/xposed_init` entry point. The original registry routed only `android`; the current implementation adds a separate main-process `com.android.systemui` route.

**Implementation requirements:**
- Add a separate Protection installer for package `com.android.systemui`.
- Gate it on both `packageName == "com.android.systemui"` and the main process name `com.android.systemui`.
- Use the SystemUI `LoadPackageParam.classLoader`.
- Resolve exact methods with API 82; verify descriptors against the target build.
- Keep the existing SystemServer Media and Voltage initialization path unchanged.
- Fail closed for an unavailable Protection hook rather than breaking SystemUI startup.

**Implemented** — `HookRegistry` has a separate SystemUI route, gated by both package name and main process name. It does not alter the existing SystemServer Media/Voltage route. The Manifest scope includes `com.android.systemui`.

**Implemented** — `ProtectionHook` hooks the target firmware's `SystemUIApplication.onCreate()` and registers one process-wide configuration receiver after Application initialization. The receiver is independent of RecentsActivity and MemoryCleanerTile.

**Implemented** — the SystemUI receiver is registered with the signature-level `UPDATE_PROTECTION_CONFIG` broadcast permission. The MT8163 Tools sender must hold this permission; SystemUI does not need to request it because the permission is checked against the sender.

**Implemented** — the SystemUI bootstrap installs the Recents and Memory Cleaner hooks described below. Automated build verification and target-device runtime validation remain pending.

### Protection configuration transport

**Implemented:**
- `ProtectionSettingsStore` mirrors the global module state and per-application settings to `protection_hook_config`.
- Application settings are keyed by package name only.
- Configuration changes are sent explicitly to `com.android.systemui`.
- The SystemUI receiver requires the signature-level sender permission and reloads the configuration.
- `ProtectionTaskMatcher` publishes immutable configuration snapshots through a volatile reference. Its three predicates share the same package-name key.
- Keep is derived from the presence of an application settings entry; it is not a persisted field.
- Hide is a separate display-filter setting and does not remove the application entry or disable implicit Keep.

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
- Match by validated package name only; the target head unit has one Android user.
- If identity cannot be established, do not guess. Preserve the original firmware behavior and do not claim the task is protected.
- Never persist settings by task ID.

## 5. Implicit Keep — dismissal paths

### Swipe

Source: `recents/views/TaskStackViewTouchHandler.java` and the firmware `SwipeHelper`.

**Confirmed** — `SwipeHelper` asks the Recents callback `canChildBeDismissed(View)` when selecting a swipe candidate and again when deciding whether the completed gesture is a dismissal. The callback also checks normal stack and animation state.

**Implemented** — the hook returns `false` only when the task identity is valid and Keep is enabled. All other cases retain the firmware result. The manual drag callback is not hooked.

### Card X

Sources:
- `recents/views/TaskViewHeader.java`
- `recents/views/TaskView.java`

**Confirmed** — the header's dismiss-button branch resolves its parent TaskView and calls `dismissTask()`.

**Implemented** — the hook suppresses only the dismiss-button branch when the application is listed and **Allow closing card via X** is disabled. When that option is enabled, the original X-button behavior is retained. Application-info, move-task, and other click paths remain untouched.

### Keyboard dismissal

Source: `recents/views/TaskStackView.java`.

**Confirmed** — `DismissFocusedTaskViewEvent` is handled separately. The handler dismisses the focused TaskView and resets focus.

**Implemented** — the hook suppresses the event only for a protected focused task and calls the firmware's `resetFocusedTask(Task)`, preserving focus-frame cleanup.

### Why a global dismissTask() hook is unsafe

**Confirmed** — `TaskView.dismissTask()` is also used by package/component cleanup and launch-failure cleanup. The dismissal event does not identify the initiating action.

**Implementation constraint** — do not globally block `dismissTask()` or filter its event consumer without distinguishing user dismissal from firmware cleanup.

## 6. Implicit Keep — Close all

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

**Implemented** — the hook scopes a per-thread cleaner state to `clearRecentsTask()`. At `deleteTaskData(task, false)`, it resolves the package identity and records whether that task is protected. For protected tasks it suppresses cache deletion, the matching `ActivityManagerWrapper.removeTask(taskId)`, and the matching `ActivityManager.forceStopPackage(packageName)`. Unprotected tasks retain the original sequence.

**Implementation requirements:**
- Match package name defensively.
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

The entries below are installed in source. Exact runtime availability still requires confirmation on the target head unit.

| Setting / action | Installed SystemUI method or boundary | Implemented behavior |
|---|---|---|
| Implicit Keep — swipe | `TaskStackViewTouchHandler.canChildBeDismissed(View)` | Reject swipe dismissal for protected tasks; preserve original checks |
| X-button exception | Dismiss-button branch in `TaskViewHeader.onClick(View)` | Suppress X unless `allow_close_via_x` is enabled |
| Implicit Keep — keyboard | `TaskStackView.onBusEvent(DismissFocusedTaskViewEvent)` | Skip protected dismissal; reset focus and focus frame |
| Implicit Keep — Close all | `TaskStackView.onBusEvent(DismissAllTaskViewsEvent)` | Use per-task dismissals for attached views; selectively remove unattached tasks |
| Hide — initial display | `RecentsView.onReload(TaskStack, boolean)` | Filter local display stack before view consumes it |
| Hide — refresh | `MultiWindowStateChangedEvent(TaskStack)` constructor | Filter before subscribers and deferred animation consume it |
| Memory Cleaner | `clearRecentsTask()`, `deleteTaskData()`, `removeTask()`, `forceStopPackage()` | Suppress all three destructive operations for protected tasks |

Exact method descriptors and target class-loader resolution must be checked against the firmware build before installing hooks.

## 11. Implementation readiness and validation

### Implemented source hooks

- SystemUI main-process routing and the `SystemUIApplication.onCreate()` bootstrap hook.
- One process-wide configuration receiver with sender-UID validation.
- Configuration model, persistence mirror, and package-name matcher.
- Implicit Keep: swipe, keyboard dismissal, and selective Close all.
- X-button handling: block by default; preserve the firmware action when `allow_close_via_x` is enabled.
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
4. Validate all three configurable settings independently, including the X-button exception.
5. Test swipe, X, keyboard dismissal, and Close all in Grid and non-Grid layouts.
5. Test Memory Cleaner with protected and unprotected tasks.
6. Test Hide from Recents on initial display and refresh, including restoration after disabling the setting.
7. Test package changes, SystemUI restart, missing task identity, animation cancellation, and repeated user actions.
8. Confirm that protected tasks never reach the destructive cleanup sequence through the protected user action.

Protection hooks are implemented in source but have not yet been runtime-validated on the target head unit.


## 12. Current persisted settings format

The current per-application JSON object contains exactly these boolean fields:

| Key | Meaning |
|---|---|
| `hide_recents` | Hide the application's card from visible Recents |
| `protect_cleaner` | Exclude the application's task from MTK Memory Cleaner cleanup |
| `allow_close_via_x` | Allow the X button to dismiss the card |

The application package name is the JSON object key. The existence of that entry defines implicit Keep; there is no `keep_recents` field.

**Implemented:** `ProtectionConfig` reads all three fields as required booleans. The parser does not supply defaults for missing fields. The settings writer emits the three current fields and no Keep field. `ProtectionTaskMatcher.shouldKeep()` returns true when a valid package has an application settings entry and the module configuration is enabled.

**Compatibility policy:** the previous `keep_recents` setting has been removed from the model, matcher, and writer. The implementation does not maintain a legacy Keep representation or migration path. Existing JSON entries must contain all three current boolean fields to be accepted by the current parser.

**Validation note:** malformed JSON or a missing/wrongly typed required field causes the current parser's JSON exception path to clear the parsed application map. Verify persisted data against the current schema before target-device testing.

## 13. Latest implementation change

Commit `2827d4333191c73c6fcfd56f96fb403094a61aa4` removed the legacy Keep setting compatibility:

- Removed the Keep field and Keep constructor parameter from `ProtectionConfig.ApplicationSettings`.
- Removed the separate Keep accessor.
- Removed the `keep_recents` JSON write.
- Changed Keep matching to application-entry membership.
- Updated settings writer call sites to the three-setting model.

The source change is committed on `development`. CI/build and target-device runtime validation must be checked independently; a source commit alone does not establish successful execution.

## 14. Primary firmware source files

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

**Final research status** — the Recents and MTK Memory Cleaner paths in the current Protection scope have been reverse-engineered. The supplemental audit in Sections 15–17 identifies additional vendor power-off/source-switch and work-profile cleanup paths that can terminate processes or remove tasks, but they remain outside the agreed Protection scope and are not proposed hook targets. Remaining work is target-device validation of the in-scope implementation and the specific vendor lifecycle questions listed in Section 17.


## 15. Additional task-removal and Recents model paths

Source snapshot: [`14vvp93/public_worker` commit `01650ab579c5215bdc30a19e94b73b0f59f4d8e1`](https://github.com/14vvp93/public_worker/commit/01650ab579c5215bdc30a19e94b73b0f59f4d8e1).

### Work-profile lock failure cleanup

Sources:
- [`WorkLockActivityController.java`](https://github.com/14vvp93/public_worker/blob/01650ab579c5215bdc30a19e94b73b0f59f4d8e1/artifacts/MtkSystemUI/jadx/sources/com/android/systemui/keyguard/WorkLockActivityController.java)
- [`KeyguardViewMediator.java`](https://github.com/14vvp93/public_worker/blob/01650ab579c5215bdc30a19e94b73b0f59f4d8e1/artifacts/MtkSystemUI/jadx/sources/com/android/systemui/keyguard/KeyguardViewMediator.java)

**Confirmed:**
- `KeyguardViewMediator` constructs `WorkLockActivityController`; its constructor registers a task-stack listener.
- `onTaskProfileLocked(taskId, userId)` starts the work-profile credential challenge in the existing task. The intent carries `userId`, and the launch options carry `taskId`.
- If `startActivityAsUser(..., -2)` returns an unsuccessful result, the controller calls `IActivityManager.removeTask(taskId)`.

This is actual **system task removal**, not a Recents-only model update. The path does not call `forceStopPackage()` or `Process.killProcess()`; it does not resolve the task's package name before removal. It is a work-profile credential/security cleanup path and is outside Protection scope. Do not suppress it to retain a card.

### Recents model-only removal

Sources:
- [`RecentsView.java`](https://github.com/14vvp93/public_worker/blob/01650ab579c5215bdc30a19e94b73b0f59f4d8e1/artifacts/MtkSystemUI/jadx/sources/com/android/systemui/recents/views/RecentsView.java)
- [`TaskStack.java`](https://github.com/14vvp93/public_worker/blob/01650ab579c5215bdc30a19e94b73b0f59f4d8e1/artifacts/MtkSystemUI/jadx/sources/com/android/systemui/shared/recents/model/TaskStack.java)

**Confirmed:**
- After a Recents drag-to-dock operation successfully starts the task with split-screen options, the transition callback calls `TaskStack.removeTask(task, null, true)`.
- `TaskStack.removeTask()` removes the item from the in-memory `mStackTaskList` and `mRawTaskList`, then notifies the view callback. It does not call `IActivityManager.removeTask()` and does not dispatch `DeleteTaskDataEvent`. This is a model update for a task moved into split-screen, not task deletion.
- `TaskStack.setTasks()` also reconciles the local model against the supplied list: keys absent from the new list are removed from the local stack and reported through `onStackTaskRemoved()`. This method does not dispatch `DeleteTaskDataEvent` or call the system task-removal API.

A missing card after a model refresh or successful dock transition is therefore not, by itself, evidence that Android removed the system task or terminated its process. The existing `DeleteTaskDataEvent` path remains the separate Recents dismissal cleanup path described in Sections 5–9.

## 16. Vendor process-termination paths outside current Protection scope

The following findings come from the decompiled target `boot-carservices.vdex` classes in `artifacts/CarServises` (one extracted DEX; JADX output). They are vendor power/source lifecycle behavior, not Recents dismissal or MTK Memory Cleaner. No Protection hook is proposed for these paths.

### ACC-off wake-lock cleanup — `CarStatusService`

Source: [`CarStatusService.java`](https://github.com/14vvp93/public_worker/blob/01650ab579c5215bdc30a19e94b73b0f59f4d8e1/artifacts/CarServises/jadx/boot-carservices_classes/sources/android/carstatus/CarStatusService.java).

**Confirmed call chain:**
1. `onAccStatusInner(0)` sets `mAcc = 0` and schedules `goingToSleep()` after `power_down_afteraccoff` (default 30,000 ms).
2. `goingToSleep()` calls `LinkManager.sendAccState(0)` and schedules the wake-lock check after 5 seconds.
3. While `mAcc == 0`, handler message 7 repeats every 5 seconds, increments `mCheckWakeLockTimes`, and calls `checkWakeLock(mCheckWakeLockTimes > 15)`. The destructive pass therefore starts on the 16th check, approximately 80 seconds after `goingToSleep()`.
4. `checkWakeLock()` reads the vendor `PowerManager.getWakeLockList()` output. For entries matching `uid=... pid=...`, it calls `Process.killProcess(pid)` for every PID other than its own. There is no package, task, or background-state check on this PID branch.
5. For entries without a matching PID, or entries attributed to the service's own PID, `findAndkillbyUid()` parses `WorkSource.<uid>`; when UID is greater than 10000 it resolves a package string through `PackageManager.getNameForUid(uid)` and calls `Util.forceStopPackage(packageName)`. The utility invokes `ActivityManager.getService().forceStopPackage(packageName, -1)`.

This is a **PID process kill** or **package force-stop**, depending on the wake-lock record. Neither branch explicitly calls `removeTask()` or mutates the Recents model. The PID branch can target a process without first resolving its package; the WorkSource branch uses UID-to-package resolution and passes user ID `-1` to the force-stop API. This vendor ACC-off/suspend watchdog is outside Protection scope and can terminate a protected app independently of the Memory Cleaner setting.

### Source/audio switching — `SourceService`

Source: [`SourceService.java`](https://github.com/14vvp93/public_worker/blob/01650ab579c5215bdc30a19e94b73b0f59f4d8e1/artifacts/CarServises/jadx/boot-carservices_classes/sources/android/sourceservice/SourceService.java).

**Confirmed call chain:**
- Binder transaction 101 posts activity-status handling to `onActivityStatus()`.
- Binder transactions 201 and 401 can post media-status handling to `onMediaStatus()`.
- When an activity/source transition changes `CurrentSource`, `onActivityStatus()` records the previous source package and schedules handler message 4 after 1,500 ms, unless the transition is a process-death event or the new package is the previous source.
- When a media-status event has duration greater than 20,000 ms, `onMediaStatus()` can call `muxMediaPlayer()`. If that changes `CurrentSource`, it also schedules the previous source package for handler message 4 after 1,500 ms.
- Handler message 4 skips values contained in `SYSTEM_PACKAGES` and the package still equal to `CurrentSource`; otherwise it calls `SourceService.forceStopPackage()`.
- That method resolves the supplied source/component string to an installed package using `Util.findPackageName()` (package-name prefix matching), then calls `forceStopPackage(resolvedPackage, -1)`.

This is a **vendor source-switch force-stop** of the previous selected audio/source package. It is not a Recents dismissal and contains no explicit system task-removal or Recents model operation. The Binder transaction entrypoints and internal trigger conditions are confirmed; the external process sending the activity-status transactions is not identified in the extracted Java sources. This path is outside Protection scope.

### Phone-link shutdown and locale restart

Sources:
- [`LinkManager.java`](https://github.com/14vvp93/public_worker/blob/01650ab579c5215bdc30a19e94b73b0f59f4d8e1/artifacts/CarServises/jadx/boot-carservices_classes/sources/android/link/LinkManager.java)
- [`CarletterInterface.java`](https://github.com/14vvp93/public_worker/blob/01650ab579c5215bdc30a19e94b73b0f59f4d8e1/artifacts/CarServises/jadx/boot-carservices_classes/sources/android/link/CarletterInterface.java)
- [`CarZlinkInterface.java`](https://github.com/14vvp93/public_worker/blob/01650ab579c5215bdc30a19e94b73b0f59f4d8e1/artifacts/CarServises/jadx/boot-carservices_classes/sources/android/link/CarZlinkInterface.java)
- [`McuService.java`](https://github.com/14vvp93/public_worker/blob/01650ab579c5215bdc30a19e94b73b0f59f4d8e1/artifacts/CarServises/jadx/boot-carservices_classes/sources/android/carsource/McuService.java)

**Confirmed:**
- `McuService` registers a `PackageMonitor` and also scans installed packages. Both paths call `LinkManager.initLinkByPackageManager()`.
- `LinkManager` selects `CarletterInterface` for `com.carletter.car` or `CarZlinkInterface` for `com.zjinnova.zlink`, provided no link interface is already selected.
- The ACC-off path in `CarStatusService.goingToSleep()` calls `LinkManager.sendAccState(0)`.
- With `CarletterInterface` selected, `sendAccState(0)` schedules handler message 2 after 500 ms. The handler calls `stopCarletter()`, which force-stops `com.carletter.car`, `com.huawei.nearby`, `com.huawei.dmsdpdevice`, `com.huawei.hisight`, and `com.huawei.hiviewlite`.
- With `CarZlinkInterface` selected, `sendAccState(0)` sends its power-off broadcast and force-stops `com.zjinnova.zlink`.
- The LinkManager receiver forwards `android.intent.action.LOCALE_CHANGED` to `localeChange(true)`. The Zlink implementation force-stops `com.zjinnova.zlink` when restart is true; the Carletter implementation's locale callback is empty.

These are **fixed-package force-stop** paths, using the vendor utility that passes user ID `-1`. They do not explicitly remove a system task or mutate Recents. Only the currently selected link interface receives these calls. ACC-off and phone-link restart behavior is outside Protection scope.

### Process-kill helpers without verified call sites

- `HProcessUtils.killAllBackgroundProcesses()` and `HProcessUtils.killBackgroundProcesses(String)` exist in both the AutoBtClient and BluetoothService decompilations and call `ActivityManager.killBackgroundProcesses(packageName)`. Repository-wide searches of the checked source set found no call sites for either helper. Treat them as **unreferenced helper definitions**, not confirmed active kill paths.
- `McuService.forceStopPackage(String)` is also present only as a private helper definition; no call site was found in that class.
- Other inspected `Process.killProcess(Process.myPid())` calls in crash handlers, `HAppUtils`, and `SysuiRestartReceiver` terminate their own process, not a selected third-party app.

No additional vendor `removeTask()` call site was found in the checked CarServices classes. The active CarServices paths above perform process termination, not explicit Recents task deletion.

## 17. Scope boundary and remaining runtime validation

The Protection scope remains unchanged: implicit Keep for the documented Recents dismissal actions, display-only Hide, the X-button exception, and the MTK Memory Cleaner exclusion. The new vendor paths are not additional Protection features and must not be hooked or suppressed without an explicit scope decision. They can nevertheless terminate an application while it remains in the Protection list during ACC-off, audio-source switching, or phone-link shutdown/restart. Protection does not promise process survival across those vendor lifecycle operations.

**Remaining runtime-validation questions:**
1. Confirm that the target build invokes the `carservices.startup(Context)` entrypoint and identify the runtime process hosting these boot-carservices services; the extracted Java source shows service registration but not the startup caller.
2. On ACC-off, record the effective `power_down_afteraccoff` value, wake-lock list formats, the 16th-check timing, and which PID/WorkSource UID records are actually killed or force-stopped.
3. Identify the Binder caller(s) for SourceService transactions 101, 201, and 401 on the target device. Verify which previous-source packages can reach handler message 4 and how `sys.navi.packages` affects the `SYSTEM_PACKAGES` exclusion.
4. Determine which phone-link interface is selected on the target unit and verify its ACC-off and locale-change force-stop behavior.
5. Confirm whether a managed/work profile exists and whether `onTaskProfileLocked()` is ever delivered. Verify the credential-launch failure path before drawing conclusions about its practical reachability.
6. After vendor `forceStopPackage()` or PID termination, observe whether the target Android build retains, removes, or rebuilds the corresponding Recents task. The vendor call sites do not explicitly request task removal, so this remains a framework/runtime question.
7. The AutoBtClient/BluetoothService background-kill helpers have no source call sites in the checked artifacts; only investigate reflective or native invocation if runtime evidence suggests they are used.
