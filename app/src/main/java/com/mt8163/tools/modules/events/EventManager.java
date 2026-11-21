package com.mt8163.tools.modules.events;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class EventManager {
    public interface Listener {
        void onEventsChanged();
    }

    private static volatile EventManager instance;
    private final EventDatabase database;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(512));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong dropped = new AtomicLong();
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

    public long getDroppedCount() {
        return dropped.get();
    }

    public void log(String module, String source, String message) {
        if (!enabled || module == null || module.trim().isEmpty()
                || source == null || message == null) return;
        enqueue(module.trim(), source.trim().isEmpty() ? "General" : source.trim(),
                message.trim(), false);
    }

    public void logLifecycle(String source, String message) {
        if (source != null && message != null) {
            enqueue("Events", source, message, true);
        }
    }

    private void enqueue(String module, String source, String message, boolean lifecycle) {
        if (message.isEmpty()) return;
        try {
            writer.execute(() -> {
                if (!lifecycle && !enabled) return;
                try {
                    database.insert(System.currentTimeMillis(), module, source, message);
                    main.post(() -> {
                        Listener current = listener;
                        if (enabled && current != null) current.onEventsChanged();
                    });
                } catch (RuntimeException ignored) {
                    dropped.incrementAndGet();
                }
            });
        } catch (RejectedExecutionException error) {
            dropped.incrementAndGet();
        }
    }

    public List<EventRecord> read(List<String> modules, int limit, long beforeId) {
        return enabled ? database.read(modules, limit, beforeId) : Collections.emptyList();
    }

    public List<EventRecord> readAll(List<String> modules) {
        return enabled ? database.readAll(modules) : Collections.emptyList();
    }

    public List<String> getModules() {
        return enabled ? database.getModules() : Collections.emptyList();
    }

    public int count() {
        return enabled ? database.count() : 0;
    }

    public void clear() {
        if (!enabled) return;
        try {
            writer.execute(() -> {
                if (!enabled) return;
                try {
                    database.clear();
                    main.post(() -> {
                        Listener current = listener;
                        if (enabled && current != null) current.onEventsChanged();
                    });
                } catch (RuntimeException error) {
                    dropped.incrementAndGet();
                }
            });
        } catch (RejectedExecutionException error) {
            dropped.incrementAndGet();
        }
    }
}
