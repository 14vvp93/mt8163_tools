# Protection Module

## Purpose

Protection controls how selected applications are represented and retained in the Android 9 Recents interface. It provides independent controls for Recents card dismissal, Recents visibility, and the MTK SystemUI Memory Cleaner.

Target platform: Android 9 (API 28), MT8163 head unit.

## Per-application settings

Each selected application has three settings.

### 1. Keep card in Recents

- **Enabled:** protect the application's Recents task from Swipe-to-dismiss, the card's X button, and **Close all**.
- **Disabled:** these Recents dismissal actions use the firmware's normal behavior.
- The setting protects the Recents task/card. It must not prevent Android from managing the application's process or lifecycle.

### 2. Hide from Recents

- **Enabled:** the application's task must not be displayed in Recents.
- **Disabled:** the application may be displayed according to the normal Recents behavior.
- Enabling this setting automatically disables **Keep card in Recents**.
- If **Keep card in Recents** is enabled, enabling this setting turns the first setting off.
- Hiding a card must not, by itself, terminate the application's process.

### 3. Protect from Memory Cleaner

- **Enabled:** the MTK SystemUI Memory Cleaner must skip tasks belonging to this application.
- **Disabled:** Memory Cleaner uses its normal behavior for this application.
- This setting is independent of the two Recents settings.
- It applies only to the inspected Memory Cleaner operation. It does not disable Android's system memory management or prevent the system from reclaiming resources.

All three settings are disabled by default.

## Agreed behavior

| Operation | Keep card in Recents | Hide from Recents | Protect from Memory Cleaner |
|---|---|---|---|
| Swipe-to-dismiss | Block dismissal | Not applicable; card is hidden | No effect |
| Card X button | Block dismissal | Not applicable; card is hidden | No effect |
| Close all | Exclude protected task | Not applicable; card is hidden | No effect |
| Display in Recents | Keep card visible | Do not display card | No effect |
| MTK Memory Cleaner | No effect | No effect | Skip this application's task |

Applications not selected in Protection retain normal firmware behavior.

## Functional constraints

- Keep the three settings independent, except for the automatic mutual exclusion between **Keep card in Recents** and **Hide from Recents**.
- Match application settings by package name and Android user ID, not by transient task ID.
- Protecting or hiding a Recents card must not itself terminate the application's process.
- Do not interfere with Android's normal process lifecycle or system memory management.
- Preserve normal firmware behavior for applications that are not protected.

## Technical implementation status

Protection behavior is specified, but implementation is not complete. Firmware reverse-engineering findings, candidate hook points, and outstanding verification tasks are maintained separately in [ANALYSIS.md](ANALYSIS.md).
