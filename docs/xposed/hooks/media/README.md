# Media module

## Purpose

The Media module preserves the active audio source state when the head unit powers off and restores the required playback state when ACC turns on.

The module runs its system hooks in `system_server`. The application stores the Snapshot and exposes its values in the Media UI.

## Snapshot

A Snapshot contains exactly three values:

| Field | Meaning |
|---|---|
| `sourcePackage` | Package name of the selected audio source |
| `playbackState` | `playing`, `paused`, or `unknown` |
| `isForeground` | Whether the source owns the current focused window |

The Snapshot is captured once per ACC OFF transition. Its values remain unchanged throughout the corresponding restore operation.

## ACC OFF

The hook observes:

- Receiver: `android.carsource.McuService$1`
- Method: `onReceive(Context, Intent)`
- Action: `android.mcu.device.action.ACC`
- Extra: `AccStatus`

The extra accepts a Boolean or Number. Boolean values are used directly; numeric zero means OFF and any nonzero value means ON. Missing or unsupported values are ignored.

The in-memory ACC state is updated independently of the module enabled state. Snapshot capture starts only on a real ON-to-OFF transition, when the module is enabled and no capture is already in progress. Duplicate OFF broadcasts do not create additional Snapshots.

At capture time:

1. Read the selected source package from `android.sourceservice.SourceInfo`.
2. Read the current focused window from `WindowManagerService.mCurrentFocus` once and determine `isForeground`.
3. Read playback state using the source-specific mechanism below.
4. Normalize the state to `playing`, `paused`, or `unknown`.
5. Send the Snapshot to the application through an explicit ordered broadcast.

### Standard media source

For sources other than `com.autochips.bluetooth`, read the playback state from `SourceInfo.getMusicInfo()` and its `state` field.

### Bluetooth source

For `com.autochips.bluetooth`, use the vendor Binder service:

- Package: `com.hcn.bluetoothservice`
- Service: `com.hcn.bluetooth.service.BluetoothMusicService`
- Action: `com.hcn.bluetooth.service.BluetoothMusicService`
- Interface descriptor: `com.hcn.bluetooth.service.IBluetoothMusicService`
- `isA2dpPlaying()`: transaction 3

Map the result as follows:

- `true` → `playing`
- `false` → `paused`
- Binder failure or timeout → `unknown`

Do not query the standard Media source and Bluetooth in parallel.

### Snapshot persistence and shutdown behavior

After the Snapshot has been captured in memory, the ACC OFF sequence is:

1. For a standard media source with state `playing`, dispatch `KEYCODE_MEDIA_PAUSE` DOWN and UP through `AudioManager`.
2. For Bluetooth, do not send PAUSE; the head unit handles Bluetooth pause itself.
3. For `paused` or `unknown`, do not send PAUSE.
4. After the PAUSE dispatch step (or its intentional omission), send the captured Snapshot to the application through the explicit ordered broadcast.
5. `MediaSnapshotReceiver` persists all three Snapshot fields synchronously and returns `RESULT_OK` only when the save succeeds.

PAUSE is sent before persistence. Therefore, a persistence failure does not undo a PAUSE that has already been dispatched. The hook must not wait for PAUSE confirmation before saving the Snapshot.

The Bluetooth snapshot connection and callback resources must be released after completion. Do not call `requestA2dp()` or `unRegMusicClientBinder()`; these methods can change routing or stop/release A2DP.

## ACC ON restoration

Restoration uses the previously persisted Snapshot. It must not capture or query a replacement Snapshot.

The `Restore background player` option controls only whether a background player's Activity is opened. It does not disable playback restoration.

| Snapshot state | `isForeground` | Restore background player | Launch Activity | Send PLAY |
|---|---:|---:|---:|---:|
| `playing` | true | Either | Yes | Yes |
| `playing` | false | Enabled | Yes | Yes |
| `playing` | false | Disabled | No | Yes |
| `paused` or `unknown` | true | Either | Yes | No |
| `paused` or `unknown` | false | Enabled | Yes | No |
| `paused` or `unknown` | false | Disabled | No | No |

Activity launch and playback restoration are independent decisions. A service may be started without opening its Activity when playback must be restored in the background.

### Standard media restoration

Use `MediaSessionManager.OnActiveSessionsChangedListener` and match the session's package name to the Snapshot `sourcePackage`.

When the Activity must be restored:

1. Register the active-sessions listener.
2. Launch the source Activity.
3. Wait for the matching MediaSession to appear.
4. If the Snapshot state is `playing`, call `transportControls.play()` on that session.

Do not perform an initial session lookup or a redundant second lookup after registering the listener in this Activity-launch path. The Activity is expected to create its MediaSession.

When the Activity must not be restored:

1. Check whether the matching MediaSession already exists.
2. If it exists and the Snapshot state is `playing`, call `transportControls.play()`.
3. If it does not exist, register the listener and wait for the matching session.

The wait is limited to five seconds. Remove the listener and release associated resources on success, timeout, or failure. Never send PLAY for `paused` or `unknown`.

### Bluetooth restoration

Bluetooth restoration uses the vendor service and its Binder API, not the standard media-player control path.

1. Launch `BtMusicActivity` only when required by the Snapshot and the background-window option.
2. When playback restoration requires the vendor service, bind explicitly to `BluetoothMusicService` with `BIND_AUTO_CREATE`. Binding the service must not implicitly launch `BtMusicActivity`.
3. Observe active MediaSessions and identify a vendor session by either exact tag:
   - `A2dpMediaBrowserService`
   - `BluetoothMediaBrowserService`
4. Use the matching session as the readiness signal for the vendor's internal MediaController. Do not separately query the initial A2DP/AVRCP connection states or register connection-state callbacks for this readiness decision.
5. If and only if the Snapshot state is `playing`, send `send_Avrcp_Cmd(0)` once the vendor session is detected.
6. For `paused` or `unknown`, do not send PLAY.
7. Unregister any temporary callback and unbind when restoration completes or fails.

Do not call `requestA2dp()` or `unRegMusicClientBinder()`. The former can request audio routing and launch Bluetooth UI; the latter can stop playback and release A2DP.

The active-session signal is indirect: the vendor service has its own listener that discovers these session tags and assigns its internal MediaController. Runtime validation is required to confirm that our listener observes the session only after the vendor controller has been assigned.

A matching MediaSession proves that the vendor media session exists; it does not, by itself, prove that an A2DP device is currently connected. Do not use session presence as a general Bluetooth connection-state result. The vendor PLAY transaction itself checks its internal A2DP state and may not execute while A2DP is disconnected. Verify this behavior on the head unit, including how the session behaves across Bluetooth disconnect/reconnect and how `persist.sys.BTMusicAutoPlay` affects a Snapshot of `paused`.

## Resource and execution rules

- Snapshot capture is guarded against duplicate ACC OFF broadcasts.
- Use one immutable Snapshot for the entire restore attempt.
- Do not use polling or periodic checks.
- Remove temporary listeners and callbacks on every completion path.
- Unbind services after the required Binder work.
- Dispatch standard-player PAUSE before sending the Snapshot for persistence.
- Do not wait for PAUSE confirmation after ACC OFF.
- Do not keep background monitoring active after restoration.

## UI and events

The Media UI displays the persisted source package, playback state, and foreground status. `MainActivity` reads the stored Snapshot when opening the module and updates the view when a new Snapshot broadcast is received. It does not persist the Snapshot itself.

The hook reports diagnostic events through Core's `EventLogger`. Events is an independent module and is not a dependency of Media.

## Implementation status

This document describes the agreed Media module behavior, including the restoration design.

The current `development` branch contains the ACC OFF hook, Snapshot capture, ordered-broadcast persistence, Bluetooth playback-state query, and Media UI display. The complete ACC ON restoration flow described above is a design requirement and must not be treated as already implemented. Verify the code against this specification before marking restoration complete.

