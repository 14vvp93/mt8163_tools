# Protection Module

## Purpose

Protection manages per-application behavior in Android Recents. Users select installed applications and configure how each selected application behaves when its task is dismissed or closed from the Recents interface.

Target platform: Android 9 (API 28), MT8163 head unit, LSPosed, Legacy Xposed API 82.

## Per-application settings

Each selected application has three settings.

### 1. Keep card in Recents

- **Enabled:** prevent the application's Recents card from being dismissed by a swipe. The card remains visible.
- **Disabled:** allow the card to be removed from Recents by a swipe.
- Removing a card must not, by itself, cause Protection to terminate the application's process.

### 2. Allow closing with the card's X button

- **Enabled:** pressing the X button on the application's Recents card is allowed to close the application.
- **Disabled:** pressing the X button must not close the application.

This setting controls the individual X action only. It does not change the behavior of the Close all action.

### 3. Hide from Recents

- **Enabled:** the application must not appear in Recents and must not have a visible Recents card.
- **Disabled:** the application may appear in Recents, subject to the other settings.
- Enabling this setting automatically disables **Keep card in Recents**.
- The hidden state takes precedence over the other Recents-card setting.
- Hiding an application from Recents must not, by itself, terminate its process.

## Action behavior

| User action | Required behavior |
|---|---|
| Swipe an application's card | Block dismissal when **Keep card in Recents** is enabled; otherwise allow the card to be removed without Protection itself terminating the process. |
| Press the card's X button | Allow closing only when **Allow closing with the card's X button** is enabled. |
| Use **Close all** | Never close any application registered in Protection, regardless of its X-button setting or other per-app settings. |
| Open Recents | Applications with **Hide from Recents** enabled must not be displayed. |
| Dismiss another application's card | Must not affect applications protected by Protection. |

The **Close all** rule is unconditional for every application registered in Protection. It is independent of the **Allow closing with the card's X button** setting.

## Setting interaction and precedence

The three settings are independent except for one enforced dependency:

1. Enabling **Hide from Recents** automatically turns **Keep card in Recents** off.
2. **Hide from Recents** takes precedence over card-retention behavior.
3. **Allow closing with the card's X button** remains unchanged when **Hide from Recents** is toggled.
4. **Close all** always skips every application registered in Protection.

## Behavior matrix

The following matrix applies when **Hide from Recents** is disabled.

| Keep card in Recents | Allow closing with X | Swipe | X button | Close all |
|---|---|---|---|---|
| Enabled | Disabled | Blocked; card remains | Blocked | Application is skipped |
| Enabled | Enabled | Blocked; card remains | Allowed | Application is skipped |
| Disabled | Disabled | Card can be removed | Blocked | Application is skipped |
| Disabled | Enabled | Card can be removed | Allowed | Application is skipped |

When **Hide from Recents** is enabled:

- **Keep card in Recents** is automatically disabled.
- No card is displayed in Recents.
- The X-button setting does not override hiding.
- **Close all** still skips the application.

## Application selection and configuration

- The user must be able to select installed applications for Protection.
- Each selected application must have its own independent settings.
- Configuration must be associated with the application's package name.
- Changing one application's settings must not modify another application's configuration.
- Applications not registered in Protection retain normal system behavior.

## Implementation requirements

Protection must remain an isolated module in the MT8163 Tools application and use the project's shared module and Xposed registration architecture.

Java hook sources belong in:

`app/src/main/java/com/mt8163/tools/xposed/hooks/protection/`

The implementation must use Legacy Xposed API 82 and target Android 9 (API 28).

Do not assume that swipe dismissal, the card's X button, and **Close all** use the same system method. Before implementing hooks, inspect the target ROM and identify the actual call paths in its Recents/SystemUI implementation and Android task-management services.

In particular, investigate:

- Recents card swipe-dismiss handling.
- The card's X-button handling.
- The **Close all** implementation and its task-selection logic.
- Task removal APIs, including `removeTask()` and related OEM-specific paths.
- The distinction between removing a task from Recents, finishing activities, and terminating an application process.
- The ROM-specific mechanism for excluding an application from Recents.

The implementation must preserve the required behavior even when the ROM uses OEM-modified SystemUI or Launcher components. Do not rely on blocking a single task-removal method until the actual call paths have been verified.

## Testing requirements

Protection tests must be independent of other module tests and remain outside the `app/` directory.

Tests must cover:

- All four combinations in the behavior matrix.
- The automatic disabling of **Keep card in Recents** when **Hide from Recents** is enabled.
- Hidden applications not appearing in Recents.
- **Close all** skipping every application registered in Protection, including applications with X-button closing enabled.
- Removing a card without Protection itself terminating the application process.
- Independent settings for multiple applications.
- Unregistered applications retaining normal behavior.

Final behavior must also be verified on the target MT8163 head unit running Android 9.

## Current status

The functional requirements and setting interactions are agreed. ROM-specific Recents behavior and hook entry points still require investigation. No Protection hook implementation is specified or implied by this document until that investigation is complete.
