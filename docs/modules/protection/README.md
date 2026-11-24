# Protection Module

## Purpose

Protection manages selected applications in the Android 9 Recents interface and the MTK SystemUI Memory Cleaner.

Target platform: Android 9 (API 28), MT8163 head unit.

## Application selection

The application picker lists installed applications with a launcher activity.

- Search filters applications by label or package name.
- System applications are excluded by default and can be included with **Display system applications**.
- The picker displays up to 10 matching results.
- Selecting an application adds it to the Protection list.

The application list is the Keep mechanism: while an application is listed and the module is enabled, its Recents task is protected from swipe dismissal, keyboard dismissal, and **Close all**. There is no separate Keep switch or persisted Keep property.

## Per-application settings

Each listed application has three configurable settings:

### Protect from Memory Cleaner

- **Enabled:** the MTK SystemUI Memory Cleaner skips the application's task and its associated destructive cleanup operations.
- **Disabled:** Memory Cleaner uses its normal behavior.
- This setting does not disable Android's global memory management.

### Hide application card in Recents

- **Enabled:** the application's card is filtered out of the visible Recents stack.
- **Disabled:** the card is displayed according to normal firmware behavior.
- Hiding is display-only: it does not remove the system task or terminate the application process.

### Allow closing card via X

- **Enabled:** the Recents card's X button can dismiss this application's task.
- **Disabled:** the X action is blocked while the application remains listed in Protection.
- This option does not enable swipe, keyboard, or **Close all** dismissal.

All three configurable settings are disabled by default. Adding an application itself enables the implicit Keep behavior; Keep is not stored as a separate setting.

## Behavior

| Operation | Application listed in Protection | Relevant setting |
|---|---|---|
| Swipe-to-dismiss | Blocked | Implicit Keep |
| Keyboard dismissal | Blocked | Implicit Keep |
| Close all | Task is retained; Recents closes to Home | Implicit Keep |
| Card X button | Blocked by default; allowed when enabled | Allow closing card via X |
| Display in Recents | Card is filtered when enabled | Hide application card in Recents |
| MTK Memory Cleaner | Task cleanup is skipped when enabled | Protect from Memory Cleaner |

Applications not listed in Protection retain normal firmware behavior.

## Functional constraints

- Keep is defined by application membership in the Protection list; there is no Keep boolean or JSON key.
- The X-button exception is independent of Memory Cleaner protection and Recents visibility.
- Match tasks using a validated package name, not a transient task ID.
- Hiding or retaining a Recents card must not itself terminate the application process.
- Preserve normal firmware cleanup and lifecycle behavior for unprotected tasks.
- The module's global enabled state gates Protection behavior.

## Technical implementation status

The application picker, local search, per-application controls, persistent settings, SystemUI configuration transport, task matcher, Recents dismissal handling, display-only filtering, and MTK Memory Cleaner exclusions are implemented in source. Automated build verification and target-device runtime validation remain required. Firmware findings and outstanding validation tasks are maintained separately in [ANALYSIS.md](ANALYSIS.md).
