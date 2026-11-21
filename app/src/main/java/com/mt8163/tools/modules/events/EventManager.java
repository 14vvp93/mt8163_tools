package com.mt8163.tools.modules.events;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EventManager {
    public interface Listener {
        void onEventsChanged();
    }

    private static volatile EventManager instance;
    private final EventDatabase database;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean enabled;
    private volatile Listener listener;

    private EventManager(Context context) {
        database = new EventDatabase(context);
        enabled = context.getSharedPreferences("mt8163_tools", Context.MODE_PRIVATE)
                .getBoolean("module_events", true);
    }

    public static EventManager get(Context context) {
        if (instance == null) {
            synchronized (EventManager.class) {
                if (instance == null) instance = new EventManager(context.getApplicationContext());
            }
        }
        return instance;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setListener(Listener value) {
        listener = value;
    }

    public void log(String module, String source, String message) {
        if (!enabled || module == null || module.trim().isEmpty()
                || source == null || message == null) return;
        final String safeModule = module.trim();
        final String safeSource = source.trim().isEmpty() ? "General" : source.trim();
        final String safeMessage = message.trim();
        if (safeMessage.isEmpty()) return;
        writer.execute(() -> {
            if (!enabled) return;
            try {
                database.insert(System.currentTimeMillis(), safeModule, safeSource, safeMessage);
                notifyListener();
            } catch (RuntimeException ignored) {
            }
        });
    }

    public List<EventRecord> read(List<String> modules, int limit, long beforeId) {
        if (!enabled) return Collections.emptyList();
        return database.read(modules, limit, beforeId);
    }

    public List<EventRecord> readAll(List<String> modules) {
        if (!enabled) return Collections.emptyList();
        return database.readAll(modules);
    }

    public List<String> getModules() {
        if (!enabled) return Collections.emptyList();
        return database.getModules();
    }

    public int count() {
        return enabled ? database.count() : 0;
    }

    public void clear() {
        if (!enabled) return;
        writer.execute(() -> {
            if (!enabled) return;
            database.clear();
            notifyListener();
        });
    }

    private void notifyListener() {
        mainHandler.post(() -> {
            Listener current = listener;
            if (enabled && current != null) current.onEventsChanged();
        });
    }
}
