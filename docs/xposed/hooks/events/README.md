# Events hooks

Documentation for event-observation hooks. Java source belongs in `app/src/main/java/com/mt8163/tools/xposed/hooks/events/`.

Events consumes events through Core's neutral interface. Other modules must not depend on Events. Define each target package and observed behavior before adding a hook.
