# Events Module

## Purpose

Events collects operational events from MT8163 Tools modules and presents them in one history.

## Event flow

- Modules submit events through `com.mt8163.tools.core.EventLogger`.
- Events from the app process are delivered locally through a package-scoped broadcast.
- Events from the LSPosed hook running in `system_server` are delivered to the same receiver.
- `EventsReceiver` accepts broadcasts only from the application UID or Android system UID.
- `EventManager` queues writes on a single worker and stores records in SQLite.
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
- Module filtering affects display, copy, and filtered export only. It does not delete records.
- Export supports the current filter or the complete history as UTF-8 TXT.

## Disabled state

When Events is disabled, the module UI is unavailable and incoming events are ignored. Existing database records are preserved. The module-enabled and module-disabled transitions are recorded.

## Event submission

Use the Core API:

```java
EventLogger.log(context, "Voltage", "voltageCorrected", "11.8 V -> 12.4 V");
```

Use stable module and source identifiers. Do not submit repeated high-frequency values unless they represent a meaningful state change.
