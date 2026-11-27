package com.mt8163.tools.modules.events;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class EventManager {
    public interface Listener {
        void onEventsChanged();
    }

    private static volatile EventManager instance;
    private final EventDatabase database;
    private final Object writeLock = new Object();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(512));
    private final ThreadPoolExecutor reader = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean notificationPending = new AtomicBoolean();
    private volatile boolean enabled;
    private volatile Listener listener;
    private long generation;

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
        synchronized (writeLock) {
            if (enabled == value) return;
            enabled = value;
            if (!value) {
                generation++;
                writer.getQueue().clear();
            }
        }
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
        if (module == null || module.trim().isEmpty()
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
        final long taskGeneration;
        synchronized (writeLock) {
            if (!enabled && !lifecycle) return;
            taskGeneration = generation;
        }
        try {
            writer.execute(() -> {
                synchronized (writeLock) {
                    if (!lifecycle && (!enabled || taskGeneration != generation)) return;
                    try {
                        database.insert(System.currentTimeMillis(), module, source, message);
                    } catch (RuntimeException error) {
                        dropped.incrementAndGet();
                        return;
                    }
                }
                notifyListener();
            });
        } catch (RejectedExecutionException error) {
            dropped.incrementAndGet();
        }
    }

    private void notifyListener() {
        if (!notificationPending.compareAndSet(false, true)) return;
        main.post(() -> {
            notificationPending.set(false);
            Listener current = listener;
            if (enabled && current != null) current.onEventsChanged();
        });
    }

    public void readAsync(List<String> modules, int limit, long beforeId,
                          Consumer<List<EventRecord>> callback) {
        if (callback == null) return;
        if (!enabled) {
            main.post(() -> callback.accept(Collections.emptyList()));
            return;
        }
        List<String> moduleFilter = modules == null ? null : new ArrayList<>(modules);
        try {
            reader.execute(() -> {
                List<EventRecord> records;
                try {
                    records = enabled
                            ? database.read(moduleFilter, limit, beforeId) : Collections.emptyList();
                } catch (RuntimeException error) {
                    records = Collections.emptyList();
                }
                List<EventRecord> result = records;
                main.post(() -> callback.accept(enabled ? result : Collections.emptyList()));
            });
        } catch (RejectedExecutionException error) {
            main.post(() -> callback.accept(Collections.emptyList()));
        }
    }

    public void readAllAsync(List<String> modules, Consumer<List<EventRecord>> callback) {
        readAsync(modules, EventDatabase.MAX_RECORDS, Long.MAX_VALUE, callback);
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
        clear(null);
    }

    public void clear(Consumer<Boolean> callback) {
        if (!enabled) {
            postClearResult(callback, false);
            return;
        }
        synchronized (writeLock) {
            generation++;
            writer.getQueue().clear();
            try {
                writer.execute(() -> {
                    boolean cleared = false;
                    synchronized (writeLock) {
                        if (enabled) {
                            try {
                                database.clearAndInsert(System.currentTimeMillis(), "Events",
                                        "clearHistory", "Event history cleared");
                                cleared = true;
                            } catch (RuntimeException error) {
                                dropped.incrementAndGet();
                            }
                        }
                    }
                    if (cleared) notifyListener();
                    postClearResult(callback, cleared);
                });
            } catch (RejectedExecutionException error) {
                dropped.incrementAndGet();
                postClearResult(callback, false);
            }
        }
    }

    private void postClearResult(Consumer<Boolean> callback, boolean cleared) {
        if (callback != null) main.post(() -> callback.accept(cleared));
    }
}
