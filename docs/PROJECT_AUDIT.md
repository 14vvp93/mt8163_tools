# Project audit

Audit baseline: development at b469654 (before the current implementation batch).

This document tracks source-level work and separates it from checks that require the target head unit.

## Completed in the current work package

- Standard MediaSession playback confirmation now rejects PLAYING callbacks whose position-update timestamp is missing or not newer than the recorded PLAY dispatch time.
- Bluetooth and standard playback use the same timestamp classification helper.
- Standard Media restoration uses one five-second total deadline instead of restarting the timeout after session discovery.
- Standard MediaSession listener cleanup no longer calls the manager while holding the restore lock.
- Core ModuleRegistry receives module definitions instead of importing concrete feature implementations.
- Module construction is moved to ModuleCatalog.
- Module UI creation and module-specific enable handling are delegated through ModuleUiContract; MainActivity no longer imports feature-specific view classes.
- Unit tests cover playback timestamp classification, Media playback-state normalization, and Voltage offset validation.
- Protection indicator accessibility descriptions reflect each setting's current state.
- The Voltage SeekBar touch area is 48 dp while its track remains resource-defined.
- Normal-text colors used for muted labels and event module/source labels meet the 4.5:1 contrast target against the panel background.
- The app manifest declares a launcher icon.
- Gradle 8.9 Wrapper scripts, properties, and JAR are included; CI and manual APK builds use the wrapper.
- Root README and project structure documentation describe the implemented architecture.
- Media has a module-level README; the Xposed Media documentation describes the timestamp limitation.
- The missing HTML preview input is restored for the existing manual preview workflow.

## Requires target-device validation

These items cannot be confirmed by repository inspection or CI:

1. Verify standard MediaSession PLAY confirmation on the head unit.
2. Verify that the vendor MediaSession updates getLastPositionUpdateTime() for Bluetooth PLAYING transitions. A position-update timestamp is not guaranteed to be the actual state-transition time.
3. Verify whether persist.sys.BTMusicAutoPlay independently starts Bluetooth playback and how that interacts with paused/unknown Snapshots.
4. Verify XSharedPreferences access from LSPosed/system_server for Media and Voltage.
5. Verify Media ACC transitions, vendor Binder transactions, callback registration/unregistration, service binding, cancellation, and timeout behavior.
6. Verify Protection Recents refresh and Memory Cleaner exclusions in the target SystemUI build.
7. Verify Voltage field correction and restoration against the actual MCU service and battery packets.
8. Verify the 1280×720 / 160 dpi UI layout and touch targets on the head unit.

## Remaining repository/tooling items

- APK Build remains manual-only. Do not add a push trigger.
- The HTML preview is a browser mockup and is not a substitute for the Android app or device validation.
- Autorun implementation is outside the current work package.

## Verification status

CI must pass after this batch. The tests added here are source-level unit tests; they do not validate Android framework behavior or vendor firmware.

No target-device runtime test is claimed by this audit.
