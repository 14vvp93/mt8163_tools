# Events Module

## Purpose

Events stores operational events submitted by MT8163 Tools modules and displays them in one history.

Events is an application module, not an Xposed hook. Producers use the Core `EventLogger` API and do not access Events storage or UI.

## Event flow

- Application-process events are delivered to the application-side receiver.
- System-process events are delivered through the SystemEventsReceiver path.
- Receivers validate the action and event fields before forwarding records to `EventManager`.
- `EventManager` serializes SQLite writes and performs history reads on a background executor.
- `EventsModuleView` displays the stored history. Callbacks from destroyed views are ignored.

## Record format

~~~text
dd.MM.yy HH:mm:ss [Module][Source]: Message
~~~

The module name is displayed in red and the source in green.

## Storage and display

- Database: `events.db`.
- Maximum history: 5,000 records; oldest records are removed after the limit is exceeded.
- The display loads 100 records at a time and loads older records when scrolling to the top.
- Database reads and export preparation run outside the UI thread.
- Failed reads do not replace the current list with an empty history.
- Module filtering affects display, copy, and filtered export only; it does not delete records.
- Export supports the current filter or the complete history as UTF-8 TXT.
- Rejected or failed writes are counted and shown in the Events view.

## Disabled state

When Events is disabled, incoming events are ignored and queued writes are discarded. An active write is allowed to finish before disabling completes. Existing database records are preserved. Events records its own enabled/disabled transitions.

## Event submission

Use the Core API:

~~~java
EventLogger.log(context, "Voltage", "voltageApplied", "11.8 V -> 12.4 V");
~~~

The module field is a display label; source is a stable event identifier. Keep messages concise and meaningful. Avoid repeated high-frequency values unless they represent a meaningful state change.
