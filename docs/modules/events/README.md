# Events Module

## Purpose

Events collects operational events from MT8163 Tools modules and presents them in one history.

## Event flow

- App-process events use a non-exported package receiver.
- LSPosed hooks in `system_server` use a separate exported receiver protected by `android.permission.DUMP`.
- Both receivers validate the action and event fields before forwarding records to `EventManager`.
- `EventManager` serializes SQLite writes and performs history reads on a background executor.
- `EventsModuleView` displays the stored history.

Other modules depend only on the Core event interface. They do not access Events storage or UI.

## Record format

```text
dd.MM.yy HH:mm:ss [Module][Source]: Message
```

The module name is displayed in red and the source in green.

## Storage and display

- Database: `events.db`.
- Maximum history: 5,000 records.
- Oldest records are removed after the limit is exceeded.
- The display loads 100 records at a time and loads older records when scrolling to the top.
- Database reads and export preparation run outside the UI thread.
- Android 9 retries database lock contention for up to 2.5 seconds before returning a lock error; failed writes are counted and logged.
- SQLite's default corruption handler logs and deletes a corrupted database. A new empty database is created on the next open; corrupted history cannot be recovered.
- Module filtering affects display, copy, and filtered export only. It does not delete records.
- Export supports the current filter or the complete history as UTF-8 TXT.
- Rejected or failed writes are counted and shown in the Events view.

## Disabled state

When Events is disabled, the module UI is unavailable and incoming events are ignored. Queued writes are discarded and an active write is allowed to finish before disabling completes. Existing database records are preserved. The module-enabled and module-disabled transitions are recorded.

## Event submission

Use the Core API:

```java
EventLogger.log(context, "Voltage", "voltageCorrected", "11.8 V -> 12.4 V");
```

Use the module name returned by `ModuleContract.getName()` and a stable source identifier. Do not submit repeated high-frequency values unless they represent a meaningful state change.
