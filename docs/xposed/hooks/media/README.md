# Media module

## Purpose

The Media module stores the active audio source state when ACC turns OFF and restores the required playback state when ACC turns ON.

The system hooks and restoration logic run in `system_server`. The application stores the Snapshot and exposes its values and background-restore settings in the Media UI.

## Snapshot

A Snapshot contains exactly three values:

| Field | Meaning |
|---|---|
| `sourcePackage` | Package name of the selected audio source |
| `playbackState` | `playing`, `paused`, or `unknown` |
| `isForeground` | Whether the source owns the current focused window |

One Snapshot is captured per ACC OFF transition. Restoration uses that persisted Snapshot without querying or replacing it.

## ACC OFF

The hook observes:

- Receiver: `android.carsource.McuService$1`
- Method: `onReceive(Context, Intent)`
- Action: `android.mcu.device.action.ACC`
- Extra: `AccStatus`

ACC OFF is handled in `beforeHookedMethod` so the source state is captured before the MCU receiver processes shutdown. ACC ON is handled in `afterHookedMethod` so restoration starts after the MCU receiver has processed the power-on event.

The extra accepts a Boolean or Number. Boolean values are used directly; numeric zero means OFF and any nonzero value means ON. Missing or unsupported values are ignored.

The first valid ACC broadcast initializes the in-memory state. An initial ON starts restoration; an initial OFF does not create a Snapshot because no ON-to-OFF transition has been observed. Snapshot capture begins only after the hook has observed ON and then OFF.

The in-memory ACC state is updated independently of the module enabled state. Snapshot capture starts only on a real ON-to-OFF transition, when the module is enabled and no capture is already in progress. Duplicate OFF broadcasts do not create additional Snapshots.

At capture time:

1. Read the selected source package from `android.sourceservice.SourceInfo`.
2. Read `WindowManagerService.mCurrentFocus` once and determine `isForeground`.
3. Read playback state using the source-specific mechanism below.
4. Normalize the state to `playing`, `paused`, or `unknown`.
5. Keep the Snapshot in memory while performing the shutdown sequence.

### Standard media source

For sources other than `com.autochips.bluetooth`, read the playback state from `SourceInfo.getMusicInfo()` and its `state` field.

If the state is `playing`, find the active `MediaController` whose package name matches `sourcePackage` and call `transportControls.pause()`. Do not send PAUSE for `paused` or `unknown`.

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

Do not query the standard Media source and Bluetooth in parallel. Do not send Bluetooth PAUSE; the head unit handles it itself.

### Snapshot persistence

After the in-memory Snapshot has been captured and the standard-player PAUSE has been sent (or intentionally omitted):

1. Send the Snapshot to the application through an explicit ordered broadcast.
2. `MediaSnapshotReceiver` synchronously persists all three fields.
3. The receiver returns `RESULT_OK` only when persistence succeeds.

PAUSE is sent before persistence. Therefore, a persistence failure does not undo a PAUSE that has already been sent. The hook does not wait for PAUSE confirmation.

The Bluetooth snapshot connection and callback resources are released after completion. Do not call `requestA2dp()` or `unRegMusicClientBinder()`; these methods can change routing or stop/release A2DP.

## ACC ON restoration

Restoration starts on an observed ACC OFF-to-ON transition or when the first valid ACC broadcast initializes the state as ON, provided the module is enabled.

The hook requests the persisted Snapshot from `MediaSnapshotReceiver` using an explicit ordered broadcast. The receiver reads the private application preferences and returns the Snapshot and current settings in the broadcast result. The Snapshot is not exposed through world-readable storage.

The two background settings are independent and default to disabled:

- `Restore background media player`
- `Restore background Bluetooth player`

Each setting controls only whether its corresponding Activity is opened when the source was in the background. Neither setting disables playback restoration.

Activity launch policy:

| Source state | Source was foreground | Corresponding background setting | Launch Activity |
|---|---:|---:|---:|
| Any playback state | Yes | Either | Yes |
| Any playback state | No | Enabled | Yes |
| Any playback state | No | Disabled | No |

Playback policy:

| Snapshot playback state | Send PLAY |
|---|---:|
| `playing` | Yes |
| `paused` | No |
| `unknown` | No |

Activity launch and playback restoration are independent. A background source with state `playing` must receive PLAY even when its background Activity setting is disabled.

### Standard media restoration

Use `MediaSessionManager.OnActiveSessionsChangedListener` and match the session package name to Snapshot `sourcePackage`.

When the Activity must be opened:

1. Register the active-sessions listener.
2. Launch the source package Activity.
3. Wait for its matching MediaSession.
4. If the Snapshot state is `playing`, call `transportControls.play()`.

Do not perform an initial active-session lookup in this Activity-launch path. The listener is registered before launching the Activity.

When the Activity must not be opened:

1. Register the active-sessions listener.
2. Check whether the matching MediaSession already exists.
3. If it exists and the Snapshot state is `playing`, call `transportControls.play()`.
4. Otherwise, wait for the matching session through the registered listener.

Registering before the single lookup closes the gap in which a session could appear between the lookup and listener registration.

The total wait is limited to five seconds. Remove the listener on success, timeout, cancellation, or failure. Never send PLAY for `paused` or `unknown`.

### Bluetooth restoration

Bluetooth restoration uses the vendor service and Binder API, not standard MediaSession transport controls.

For `playing`:

1. Open `BtMusicActivity` only when the source was foreground or `Restore background Bluetooth player` is enabled.
2. Bind explicitly to `BluetoothMusicService` with `BIND_AUTO_CREATE`. Binding must not launch the Activity.
3. Wait for a MediaSession with either exact tag:
   - `A2dpMediaBrowserService`
   - `BluetoothMediaBrowserService`
4. On the isolated Bluetooth worker thread, check:
   - `isA2dpConnected()`: transaction 1
   - `isAvrcpConnected()`: transaction 2
5. Only when both return true, send `send_Avrcp_Cmd(0)` (PLAY): transaction 5. Send it once.

The total timeout is ten seconds, covering both MediaSession readiness and the Binder checks. The operation uses one single-thread worker. If a Binder transaction remains blocked after timeout, the operation is cancelled, listeners and service binding are cleaned up, and any late result is ignored. A second worker is not started or queued while the first Binder call is still blocked; a concurrent attempt ends without sending PLAY.

For `paused` or `unknown`, do not bind for playback restoration and do not send PLAY. The Activity may still be opened according to the foreground and Bluetooth background setting rules.

Do not call `requestA2dp()` or `unRegMusicClientBinder()`. The former can request audio routing and launch Bluetooth UI; the latter can stop playback and release A2DP.

The vendor service has its own active-session listener and assigns its internal MediaController after discovering the session tags. Our listener is only an indirect readiness signal; validate on the head unit that the vendor controller is assigned before the PLAY transaction is sent.

Also verify the effect of `persist.sys.BTMusicAutoPlay`. Vendor AutoPlay may independently send PLAY when Bluetooth reconnects, including when the stored Snapshot is `paused` or `unknown`.

## Resource and execution rules

- No polling or periodic checks.
- Snapshot capture occurs once per ACC ON-to-OFF transition.
- Restoration listeners exist only for the duration of a restore attempt.
- Remove listeners and callbacks on success, timeout, cancellation, or failure.
- Unbind the Bluetooth service after the required Binder work.
- An operation can transition from ACTIVE to CANCELLED only before PLAY is committed. A cancellation that wins this transition prevents PLAY; once PLAY is committed, a later cancellation cannot retract the in-flight command.
- Do not keep background monitoring active after restoration.
- The dynamic Media configuration receiver accepts broadcasts only from callers holding the app's signature-level `UPDATE_MEDIA_CONFIG` permission.

## UI and events

The Media UI displays the persisted source package, playback state, and foreground status. It provides independent background Activity settings for standard media and Bluetooth; both default to disabled.

`MainActivity` reads the stored Snapshot when opening the module and updates the view when a new Snapshot broadcast is received. It does not persist the Snapshot itself.

The hook reports diagnostic events through Core's `EventLogger`. System-process events are sent with the `SYSTEM_EVENT` action and received by `SystemEventsReceiver`; application-process events use `EventsReceiver`. Both paths pass validated records to `EventManager`, which writes them asynchronously to the Events SQLite database.

Media diagnostic records use module name `Media`. They cover:
- ACC hook installation, valid state transitions, and rejected ACC values
- Configuration changes and hook/configuration errors
- Snapshot capture, Bluetooth snapshot binding, persistence success, and persistence failure
- Standard-player PAUSE dispatch and missing-session/error cases
- Restore request, selected source/state, and Activity launch decisions
- Standard MediaSession listener registration, matching session detection, PLAY dispatch, timeout, cancellation, and failure
- Bluetooth vendor MediaSession detection, listener registration, service binding, A2DP/AVRCP connection results, PLAY dispatch, timeout, cancellation, and failure

Events are retained only when the Events module is enabled. If Events is disabled, incoming records are intentionally ignored. The Events module remains independent: Media does not require its UI or database APIs and communicates only through Core's event broadcast contract.

## Implementation status

The `development` branch contains the ACC OFF Snapshot flow, standard-player PAUSE through `MediaSession.TransportControls`, ordered-broadcast persistence, ACC ON restoration, separate background Activity settings, standard MediaSession restoration, and Bluetooth Binder restoration.

Runtime validation is still required on the target head unit, especially for Bluetooth MediaSession readiness, Binder transaction behavior, and vendor AutoPlay.
