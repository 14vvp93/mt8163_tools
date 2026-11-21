package com.mt8163.tools.modules.events;

public final class EventRecord {
    public final long id;
    public final long timestamp;
    public final String module;
    public final String source;
    public final String message;

    public EventRecord(long id, long timestamp, String module, String source, String message) {
        this.id = id;
        this.timestamp = timestamp;
        this.module = module;
        this.source = source;
        this.message = message;
    }
}
