# Media hooks

The Media hook monitors the firmware Audio Source service in the Android system process.

## Stage 1

The hook observes `android.sourceservice.SourceService.callManager()` and reads the initial state through `android.sourceservice.SourceInfo`.

It publishes:
- selected source package;
- raw playback state;
- UI updates through an explicit package broadcast;
- diagnostic events through Core's `EventLogger`.

The hook does not replace `SourceInfo.OnEventListener`, issue playback commands, manage ACC transitions, or access the Bluetooth Binder service.

## Events

Media events are sent from `system_server` through `EventLogger` and the existing system-event broadcast channel. The Events module stores them when it is enabled.

| Source | Meaning |
|---|---|
| `sourceChanged` | Selected Audio Source package changed |
| `playbackStateChanged` | Raw playback state changed |
| `snapshotReceived` | Initial SourceInfo snapshot was read |
| `hookError` | Hook setup, configuration, snapshot, receiver, or broadcast failed |

Repeated source or playback values are not logged. A source change resets the cached playback state to unknown; that reset is not recorded as a separate playback transition.
