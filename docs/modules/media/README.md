# Media Module

## Purpose

Media captures the selected audio source and playback state around ACC transitions, persists one shared Snapshot, and restores the required playback state when ACC turns ON.

## UI

The Media screen provides:

- Independent background Activity restoration settings for Media Player and Bluetooth.
- Playback-resumed Toast notifications.
- One shared last-Snapshot display. The Snapshot appears only in the matching Media Player or Bluetooth section; the other section displays Unknown when the saved source does not match or has no data.

The active Media Player package name comes from the shared Snapshot. The app label for a resumed Toast is resolved through Android PackageManager; no separate package-name tracking is maintained.

## Snapshot

The shared record contains:

- Source package.
- Normalized playback state: `playing`, `paused`, or `unknown`.
- Foreground/background state.

A new Snapshot replaces the previous record. Separate Media Player and Bluetooth Snapshot stores are not used.

## ACC behavior

At ACC OFF, the hook captures the selected source, playback state, and foreground state before the MCU receiver handles shutdown. A playing standard MediaSession is paused; Bluetooth pause is left to the head unit.

At ACC ON, the saved Snapshot is read. PLAY is sent only when the saved playback state is `playing`. Activity launch is independent and follows the foreground state and the matching background-restore setting.

## Standard MediaSession restoration

The matching package MediaSession is found through MediaSessionManager. Its callback confirms playback. Session discovery and playback confirmation share one five-second deadline.

## Bluetooth restoration

Bluetooth uses the vendor BluetoothMusicService Binder interface and a vendor MediaSession as a playback-confirmation signal. A2DP and AVRCP must both be connected before the one-second pre-PLAY delay starts. The full Bluetooth restoration operation has a 30-second deadline.

The MediaSession `getLastPositionUpdateTime()` value is used to reject stale PLAYING callbacks. It is a position-update timestamp, not a guaranteed state-transition timestamp; validate the vendor implementation on the target head unit.

## Toast behavior

- Media Player: the resolved application label followed by “Playback resumed”.
- Bluetooth: “Bluetooth: Playback resumed”.
- Failed restoration: failure Toast.
- Skipped or cancelled restoration: no resumed Toast.

Toast display is controlled by the Media playback-notifications setting and the module's runtime state.

## Implementation

- Module UI and configuration: `modules/media`.
- Restoration coordinator: `MediaRestorer`.
- System-process hook: [MediaHook](../../xposed/hooks/media/README.md).
- Snapshot receiver: `MediaSnapshotReceiver`.
- Bluetooth Binder client: `BluetoothMusicClient`.

Runtime verification of MediaSession and vendor Bluetooth behavior remains pending on the target head unit.
