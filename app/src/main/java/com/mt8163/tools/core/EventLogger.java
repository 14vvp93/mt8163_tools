package com.mt8163.tools.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class EventLogger {
    public static final String ACTION_EVENT = "com.mt8163.tools.action.EVENT";
    public static final String ACTION_SYSTEM_EVENT = "com.mt8163.tools.action.SYSTEM_EVENT";
    public static final String EXTRA_MODULE = "event_module";
    public static final String EXTRA_SOURCE = "event_source";
    public static final String EXTRA_MESSAGE = "event_message";

    private static final String TARGET_PACKAGE = "com.mt8163.tools";
    private static final int MAX_MODULE_LENGTH = 80;
    private static final int MAX_SOURCE_LENGTH = 80;
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_PENDING_EVENTS = 100;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<PendingEvent> PENDING = new ArrayDeque<>();

    private static Context systemContext;
    private static BroadcastReceiver bootReceiver;
    private static boolean bootReceiverRegistered;
    private static boolean systemBootCompleted;

    private EventLogger() {
    }

    public static void log(Context context, String module, String source, String message) {
        if (context == null || !valid(module, MAX_MODULE_LENGTH)
                || !valid(source, MAX_SOURCE_LENGTH)
                || !valid(message, MAX_MESSAGE_LENGTH)) return;

        String packageName;
        try {
            packageName = context.getPackageName();
        } catch (RuntimeException ignored) {
            return;
        }

        Intent intent = createIntent(packageName, module, source, message);
        if (!"android".equals(packageName)) {
            send(context, intent, false);
            return;
        }

        if (!isSystemBootCompleted(context)) {
            synchronized (LOCK) {
                if (!systemBootCompleted) {
                    if (PENDING.size() == MAX_PENDING_EVENTS) PENDING.removeFirst();
                    PENDING.addLast(new PendingEvent(intent));
                    return;
                }
            }
        }
        send(context, intent, true);
    }

    private static Intent createIntent(String packageName, String module,
                                       String source, String message) {
        String action = "android".equals(packageName)
                ? ACTION_SYSTEM_EVENT : ACTION_EVENT;
        Intent intent = new Intent(action);
        intent.setPackage(TARGET_PACKAGE);
        intent.putExtra(EXTRA_MODULE, module.trim());
        intent.putExtra(EXTRA_SOURCE, source.trim());
        intent.putExtra(EXTRA_MESSAGE, message.trim());
        return intent;
    }

    private static boolean isSystemBootCompleted(Context context) {
        synchronized (LOCK) {
            if (systemBootCompleted) return true;
            if (systemContext == null) systemContext = context;
            registerBootReceiverLocked();
            if (readBootCompleted()) {
                systemBootCompleted = true;
                unregisterBootReceiverLocked();
                flushPendingLocked();
            }
            return systemBootCompleted;
        }
    }

    private static void registerBootReceiverLocked() {
        if (bootReceiverRegistered || systemContext == null) return;

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                    return;
                }
                synchronized (LOCK) {
                    systemBootCompleted = true;
                    unregisterBootReceiverLocked();
                    flushPendingLocked();
                }
            }
        };

        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
            systemContext.registerReceiverAsUser(
                    receiver, UserHandle.SYSTEM, filter, null, null);
            bootReceiver = receiver;
            bootReceiverRegistered = true;
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean readBootCompleted() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method get = properties.getMethod("get", String.class, String.class);
            return "1".equals(get.invoke(null, "sys.boot_completed", "0"));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static void unregisterBootReceiverLocked() {
        if (!bootReceiverRegistered || systemContext == null || bootReceiver == null) return;
        try {
            systemContext.unregisterReceiver(bootReceiver);
        } catch (RuntimeException ignored) {
        }
        bootReceiver = null;
        bootReceiverRegistered = false;
    }

    private static void flushPendingLocked() {
        if (!systemBootCompleted || PENDING.isEmpty() || systemContext == null) return;
        List<PendingEvent> pending = new ArrayList<>(PENDING);
        PENDING.clear();
        for (PendingEvent event : pending) send(systemContext, event.intent, true);
    }

    private static void send(Context context, Intent intent, boolean systemProcess) {
        try {
            if (systemProcess) {
                context.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
            } else {
                context.sendBroadcast(intent);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean valid(String value, int maxLength) {
        return value != null && !value.trim().isEmpty() && value.length() <= maxLength;
    }

    private static final class PendingEvent {
        final Intent intent;

        PendingEvent(Intent intent) {
            this.intent = intent;
        }
    }
}
