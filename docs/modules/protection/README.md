# Protection Module

## Purpose

Protection controls how selected applications behave in Android Recents. The user chooses installed applications and configures each application's Recents behavior independently.

The module is intended to prevent selected applications from being closed accidentally through individual Recents actions or the system-wide **Close all** action. It can also hide selected applications from Recents entirely.

Target platform: Android 9 (API 28), MT8163 head unit.

## Per-application settings

Each application selected for Protection has three settings.

### 1. Keep card in Recents

- **Enabled:** prevent the application's Recents card from being dismissed by a swipe. The card remains visible.
- **Disabled:** allow the card to be removed from Recents by a swipe.
- Removing a card must not, by itself, cause Protection to terminate the application's process.

### 2. Allow closing with the card's X button

- **Enabled:** pressing the X button on the application's Recents card is allowed to close the application.
- **Disabled:** pressing the X button must not close the application.

This setting controls the individual X action only. It does not change the behavior of **Close all**.

### 3. Hide from Recents

- **Enabled:** the application must not appear in Recents and must not have a visible Recents card.
- **Disabled:** the application may appear in Recents, subject to the other settings.
- Enabling this setting automatically disables **Keep card in Recents**.
- The hidden state takes precedence over card-retention behavior.
- Hiding an application from Recents must not, by itself, terminate its process.

## System actions

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

## Application management

- The user must be able to select installed applications for Protection.
- Each selected application must have its own independent settings.
- Configuration must be associated with the application's package name.
- Changing one application's settings must not modify another application's configuration.
- Applications not registered in Protection retain normal system behavior.

## Runtime behavior requirements

- Protection must distinguish removing a task from Recents, finishing activities, and terminating an application process.
- Removing or hiding a Recents card must not cause Protection itself to terminate the application process.
- The **Close all** operation must exclude every application registered in Protection.
- The module must support the target ROM's actual Recents implementation, including OEM-specific behavior.
- Protection must remain isolated from other MT8163 Tools modules.

## Verification requirements

Before implementation, inspect the target ROM and establish how its Recents interface handles:

- Swipe dismissal.
- The card's X button.
- The **Close all** action and its task-selection logic.
- Hiding or excluding applications from Recents.
- Task removal APIs, including `removeTask()` and related OEM-specific paths.

Do not assume these actions use the same system method. Verify the actual call paths in SystemUI, Launcher, and Android task-management services before selecting an implementation approach.

## Testing requirements

Protection tests must be independent of other module tests and remain outside the `app/` directory.

Tests must cover:

- All four combinations in the behavior matrix.
- Automatic disabling of **Keep card in Recents** when **Hide from Recents** is enabled.
- Hidden applications not appearing in Recents.
- **Close all** skipping every application registered in Protection, including applications with X-button closing enabled.
- Removing a card without Protection itself terminating the application process.
- Independent settings for multiple applications.
- Unregistered applications retaining normal behavior.

Final behavior must also be verified on the target MT8163 head unit running Android 9.

## Current status

The functional requirements and setting interactions are agreed. The target ROM's Recents behavior and implementation approach still require investigation. No implementation has been started.
