# Project Audit

## Audit scope

- Repository: `14vvp93/mt8163_tools`
- Branch: `development`
- Audited revision: `33c69db882bcf6e68fd64296f681fe6f49ff8103`
- Audit type: static source, resource, documentation, build configuration, and CI review
- No source files or application behavior were changed as part of this audit.

This report records findings observed at the audited revision. Runtime behavior that depends on the head unit, Android framework, firmware Binder services, or the installed LSPosed version must be verified on the target device before implementation decisions are finalized.

## Executive summary

The project has a sound foundation: feature packages are separated, Xposed hooks are grouped by module, the app uses Legacy Xposed API 82, configuration broadcasts are signature-protected, event logging is separated from the Events UI, and APK builds are not part of routine CI.

A rewrite is not justified. The most important areas to verify or improve are:

1. Cross-process preference readability for Xposed hooks.
2. Media Bluetooth profile state restoration.
3. Protection hook installation status and failure handling.
4. Asynchronous event database error handling.
5. Documentation accuracy and missing automated tests.

## Findings by priority

### High priority

#### 1. Cross-process preferences may silently become unreadable

**Files**
- `app/src/main/java/com/mt8163/tools/modules/voltage/VoltageSettingsStore.java`
- `app/src/main/java/com/mt8163/tools/modules/media/MediaSettingsStore.java`
- `app/src/main/java/com/mt8163/tools/modules/protection/ProtectionSettingsStore.java`

**Observation**

The stores attempt to use `Context.MODE_WORLD_READABLE`, catch `SecurityException`, and fall back to `MODE_PRIVATE`. On Android N and later, `MODE_WORLD_READABLE` is deprecated and throws. LSPosed provides an XSharedPreferences compatibility mechanism enabled through the `xposedsharedprefs` metadata entry; the project manifest contains this metadata.

**Risk**

If the LSPosed compatibility path is unavailable, misconfigured, or behaves differently in the installed runtime, the private fallback may prevent hooks in other processes from reading the expected preferences. Because the fallback is silent, the app may appear to save settings while the hook sees stale or default values.

**Recommended action**

- Verify preference readability with the actual LSPosed version installed on the head unit.
- Keep the `xposedsharedprefs` metadata and verify that it is recognized by the runtime.
- Do not silently treat a private fallback as equivalent to cross-process-readable storage.
- Log a clear diagnostic when the intended cross-process configuration path is unavailable.
- Prefer the existing explicit, signature-protected configuration broadcasts for live updates; retain a verified initial-state mechanism for process startup.

**Status:** Runtime verification required; this is a compatibility risk, not a confirmed device failure.

#### 2. Media Bluetooth restoration may miss already-connected profiles

**File**
- `app/src/main/java/com/mt8163/tools/modules/media/MediaRestorer.java`

**Observation**

The implementation declares a `queryBoolean()` helper but has no call site for it. The restoration flow relies on callback events to update the A2DP and AVRCP connection states. The code uses a `30_000L` timeout, while the Media hook documentation describes a 10-second timeout and an initial query of A2DP/AVRCP state.

**Risk**

If a Bluetooth profile is already connected before callback registration and the vendor service does not replay its current state to newly registered callbacks, the restorer may wait for a state transition that never arrives and eventually time out.

**Recommended action**

- Inspect the actual vendor Binder service contract and callback behavior.
- If supported, register callbacks first and then query the current A2DP and AVRCP states.
- Handle races between the initial query and callback delivery so a stale query result cannot overwrite a newer callback state.
- Align the timeout and documented behavior with the verified implementation.
- Update the Media hook documentation after the behavior is confirmed.

**Status:** Vendor-service behavior must be verified before changing the restoration sequence.

### Medium-high priority

#### 3. Protection hook installation can report success after partial failure

**File**
- `app/src/main/java/com/mt8163/tools/xposed/hooks/protection/ProtectionHook.java`

**Observation**

Hook installation catches failures for individual hooks, but `hooksInstalled` is set to `true` after the installation attempts regardless of whether all required hooks were installed successfully.

**Risk**

A partial or complete installation failure can be represented as a successful installation. This can suppress safe retry opportunities and make Protection appear active when one or more interception points are missing.

**Recommended action**

- Track installation results per hook.
- Distinguish complete, partial, and failed installation states.
- Log the specific hook and exception through the project event/logging mechanism.
- Retry only hooks for which retrying is known to be safe; avoid blindly installing duplicates.

### Medium priority

#### 4. Event database read failures may leave the UI waiting

**File**
- `app/src/main/java/com/mt8163/tools/modules/events/EventManager.java`

**Observation**

`readAsync()` performs `database.read()` on a background executor without handling runtime database exceptions around the operation.

**Risk**

If the database read throws, the callback may not be delivered. UI state such as `loadingOlder` can remain active because the completion path is skipped.

**Recommended action**

- Catch database read failures at the asynchronous boundary.
- Deliver a defined failure or safe empty result on the main thread.
- Log the exception.
- Ensure every asynchronous request reaches a completion path, including failures.

#### 5. Protection application picker performs work on the UI thread

**File**
- `app/src/main/java/com/mt8163/tools/modules/protection/ProtectionModuleView.java`

**Observation**

`loadInstalledApplications()` retrieves application labels and sorts the installed application list on the UI thread. The query requests `GET_META_DATA`, although the picker does not need application metadata for its displayed information.

**Risk**

Application enumeration and label resolution can delay screen rendering or cause visible UI jank, especially on the target head unit.

**Recommended action**

- Load and sort the application list on a background executor.
- Return the completed result to the UI thread before updating views.
- Remove `GET_META_DATA` if no required picker behavior depends on it.
- Keep the flow event-driven; do not introduce polling or timer-based refreshes.

#### 6. Some persistence methods ignore commit results

**Files**
- `app/src/main/java/com/mt8163/tools/modules/voltage/VoltageSettingsStore.java`
- `app/src/main/java/com/mt8163/tools/modules/media/MediaSettingsStore.java`
- `app/src/main/java/com/mt8163/tools/core/PreferencesManager.java`

**Observation**

Some methods call `SharedPreferences.Editor.commit()` without checking its boolean result. The Protection store handles persistence results more carefully.

**Risk**

The UI or in-memory configuration may indicate that a setting was saved even when the persistent write failed.

**Recommended action**

- Return the persistence result from store methods.
- Publish or apply the new configuration only after a successful write, where persistence is a prerequisite.
- Surface or log failures instead of silently reporting success.
- Keep the implementation consistent across stores.

#### 7. Core and UI are coupled to concrete feature modules

**Files**
- `app/src/main/java/com/mt8163/tools/core/ModuleRegistry.java`
- `app/src/main/java/com/mt8163/tools/ui/MainActivity.java`

**Observation**

`ModuleRegistry` imports and constructs concrete feature modules. `MainActivity` checks module IDs and invokes concrete module view classes. This conflicts with the dependency direction described in `docs/PROJECT_STRUCTURE.md`, which says Core should not depend on feature implementations.

**Risk**

Adding or changing a feature module requires editing central infrastructure and UI dispatch code. This weakens module isolation and makes the documented architecture inaccurate.

**Recommended action**

- Keep concrete module construction in the application composition/registration layer.
- Expose the smallest common module and UI-binding contracts needed by the registry and activity.
- Make `MainActivity` dispatch through the common binding contract rather than feature-specific type checks.
- Do not introduce a dependency-injection framework solely to solve this issue.

### UI, accessibility, and product polish

#### 8. Voltage SeekBar touch target is small

**File**
- `app/src/main/res/layout/module_voltage.xml`

**Observation**

The SeekBar view height is 24dp.

**Risk**

The interactive touch area is smaller than the commonly recommended 48dp target and may be difficult to operate accurately on a head unit.

**Recommended action**

Increase the clickable view area to approximately 48dp while preserving the visible track, thumb dimensions, and approved color styling. The Voltage screen must continue to fit without vertical scrolling.

#### 9. Some text colors may not meet normal-text contrast guidance

**Files**
- `app/src/main/res/values/colors.xml`
- Related layouts using these colors

**Observation**

Approximate contrast ratios against the `#202328` panel background:
- `text_muted #777F89`: 3.89:1
- `neon_red #E53935`: 3.73:1

These are below the 4.5:1 WCAG contrast target for normal-sized text.

**Recommended action**

- Introduce brighter text-specific colors for muted text and accent labels.
- Preserve the existing neon red for decorative accents, fills, and glow where it is not used as normal text.
- Check actual text sizes and states before changing the palette.

#### 10. Protected-app indicator has static accessibility text

**File**
- `app/src/main/res/layout/item_protected_application.xml`

**Observation**

The indicator's `contentDescription` is static even though the represented protection state changes.

**Recommended action**

Update the accessibility description when the state changes so assistive technology announces the current protected/unprotected state.

#### 11. Application icon is not declared

**File**
- `app/src/main/AndroidManifest.xml`

**Observation**

The manifest does not declare `android:icon`, and no app icon resource was found during the audit.

**Recommended action**

Add a proper launcher icon resource and reference it from the manifest.

#### 12. Autorun module is currently a placeholder

**Observation**

The Autorun module is represented in the project but its functionality is not implemented.

**Recommended action**

Choose a product behavior: clearly label it as planned/disabled, or hide it until implementation is ready. This is a product decision, not a functional defect in the existing modules.

## Documentation findings

#### 13. Root README is effectively empty

**File**
- `README.md`

**Observation**

The file contains only a newline.

**Recommended action**

Add a concise project overview, platform/runtime requirements, module summary, and links to the detailed documentation.

#### 14. Project structure documentation is out of sync

**File**
- `docs/PROJECT_STRUCTURE.md`

**Observation**

Examples of drift:
- The document says Core does not depend on feature implementations, but `ModuleRegistry` currently constructs concrete modules.
- The Media UI description refers to snapshot state, while the current `module_media.xml` contains only two switches.
- Some documented hook paths and package descriptions do not fully match the current source tree.

**Recommended action**

Update the document only after architectural decisions are made, and describe the actual implementation rather than the intended future architecture.

#### 15. Events module documentation contains an invalid API example

**File**
- `docs/modules/events/README.md`

**Observation**

The example calls `ModuleContract.getName()`, which is not part of the current interface.

**Recommended action**

Replace the example with code that compiles against the current `ModuleContract`.

#### 16. Media hook documentation does not match the implementation

**File**
- `docs/xposed/hooks/media/README.md`

**Observation**

The documentation describes a 10-second timeout and initial A2DP/AVRCP state queries. The implementation uses a 30-second timeout and does not currently call the declared `queryBoolean()` helper.

**Recommended action**

After verifying the vendor Binder behavior and deciding the correct restoration sequence, update the documentation to match the code.

## Build, CI, and test findings

#### 17. Unit-test task currently has no test sources

**Evidence**
- CI run: [45894417940](https://github.com/14vvp93/mt8163_tools/actions/runs/45894417940)
- Audited commit: `33c69db`

**Observation**

The CI run succeeded and lint passed, but `testDebugUnitTest` reported `NO-SOURCE`. No `src/test` or `src/androidTest` test source files were found in the audited tree.

**Recommended action**

Add focused unit tests for pure logic and state transitions, especially Protection matching, Media state mapping/restoration logic, and Voltage configuration validation. Keep routine CI limited to checks and tests; do not add automatic APK builds.

#### 18. HTML preview workflow references a missing file

**Files**
- `.github/workflows/html-preview.yml`
- Expected path: `preview/index.html`

**Observation**

The workflow expects `preview/index.html`, but that file is absent from the repository tree at the audited revision.

**Risk**

A manual run of this workflow is expected to fail unless the file is generated or supplied by another step.

**Recommended action**

Either restore the intended preview file and keep the workflow, or remove/adjust the workflow if the preview feature is no longer used. Do not confuse this HTML preview workflow with the Android UI implementation.

#### 19. Gradle wrapper is missing

**Observation**

The repository does not contain `gradlew`, `gradlew.bat`, or `gradle/wrapper/gradle-wrapper.properties`. CI installs Gradle 8.9 directly.

**Risk**

Build tooling is less reproducible across local environments and CI.

**Recommended action**

Consider adding the Gradle wrapper and pinning the wrapper distribution. Verify compatibility with the project's Android Gradle Plugin before changing Gradle versions.

## Existing strengths to preserve

- Feature modules are separated into dedicated packages.
- Xposed hooks are grouped by feature module.
- The project uses Legacy XposedBridge API 82 as agreed.
- Configuration broadcasts use a signature-level permission.
- Event logging is separated from the Events UI.
- SQLite operations use separate executors.
- Event history is bounded.
- UI styling is largely resource-driven.
- The Voltage screen is designed to fit without vertical scrolling.
- Routine CI does not build APKs.
- Third-party dependencies are limited.
- The selected sidebar item uses the approved subtle red glow; ordinary buttons and interactive rows have no red outline.
- Input styling is intentionally separate: inactive inputs use a gray outline, while focused inputs use a red outline and glow.

## Recommended implementation order

### Stage 1 — Runtime and hook reliability
1. Verify cross-process preference access on the target LSPosed runtime.
2. Verify the vendor Bluetooth Binder service's callback and state-query behavior.
3. Correct Protection hook installation-state tracking and failure reporting.
4. Ensure asynchronous Events reads always complete with a defined success/failure result.

### Stage 2 — Persistence and UI responsiveness
1. Check and propagate preference commit results.
2. Move installed-application enumeration and sorting off the UI thread.
3. Increase the Voltage SeekBar touch target without changing its visual styling or introducing scrolling.

### Stage 3 — Architecture consistency
1. Remove concrete feature-module construction from Core.
2. Replace feature-specific dispatch in MainActivity with a common binding contract.
3. Update the architecture documentation to match the implemented dependency direction.

### Stage 4 — Documentation, accessibility, and project quality
1. Correct the Events and Media documentation examples and behavior descriptions.
2. Add a useful root README.
3. Resolve the missing HTML preview input or retire the unused workflow.
4. Add focused tests while keeping APK builds manual.
5. Add the launcher icon and improve text contrast/accessibility after visual verification.

## Verification boundaries

This was a static audit of the repository at the recorded commit. It did not include:
- Running the app on the MT8163 head unit.
- Verifying LSPosed/XSharedPreferences behavior on the installed runtime.
- Exercising the vendor Bluetooth Binder service.
- Measuring actual UI performance on device.
- Building or installing an APK as part of the audit.

Treat runtime-dependent findings as hypotheses to verify, not as confirmed device failures. Update this report as findings are verified and fixes are implemented.
