package com.mt8163.tools.modules.events;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EventDatabase extends SQLiteOpenHelper {
    public static final int MAX_RECORDS = 5000;
    private static final String DB_NAME = "events.db";
    private static final int DB_VERSION = 1;

    public EventDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, module TEXT NOT NULL, source TEXT NOT NULL, message TEXT NOT NULL)");
        db.execSQL("CREATE INDEX events_module_id ON events(module, id DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public synchronized void insert(long timestamp, String module, String source, String message) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("timestamp", timestamp);
            values.put("module", module);
            values.put("source", source);
            values.put("message", message);
            db.insertOrThrow("events", null, values);
            db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT " + MAX_RECORDS + ")");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<EventRecord> read(List<String> modules, int limit, long beforeId) {
        List<EventRecord> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        StringBuilder selection = new StringBuilder("id < ?");
        List<String> args = new ArrayList<>();
        args.add(Long.toString(beforeId));
        if (modules != null && !modules.isEmpty()) {
            selection.append(" AND module IN (");
            for (int i = 0; i < modules.size(); i++) {
                if (i > 0) selection.append(',');
                selection.append('?');
                args.add(modules.get(i));
            }
            selection.append(')');
        }
        try (Cursor cursor = db.query("events", null, selection.toString(),
                args.toArray(new String[0]), null, null, "id DESC", Integer.toString(limit))) {
            while (cursor.moveToNext()) {
                result.add(new EventRecord(cursor.getLong(0), cursor.getLong(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4)));
            }
        }
        return result;
    }

    public synchronized List<EventRecord> readAll(List<String> modules) {
        return read(modules, MAX_RECORDS, Long.MAX_VALUE);
    }

    public synchronized void clear() {
        getWritableDatabase().delete("events", null, null);
    }

    public synchronized int count() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized List<String> getModules() {
        List<String> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT DISTINCT module FROM events ORDER BY module COLLATE NOCASE", null)) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return result;
    }
}
