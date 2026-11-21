package com.mt8163.tools.core;

import android.content.Context;
import android.content.Intent;

public final class EventLogger {
    public static final String ACTION_EVENT = "com.mt8163.tools.action.EVENT";
    public static final String ACTION_SYSTEM_EVENT = "com.mt8163.tools.action.SYSTEM_EVENT";
    public static final String EXTRA_MODULE = "event_module";
    public static final String EXTRA_SOURCE = "event_source";
    public static final String EXTRA_MESSAGE = "event_message";
    private static final String TARGET_PACKAGE = "com.mt8163.tools";

    private EventLogger() {
    }

    public static void log(Context context, String module, String source, String message) {
        if (context == null || module == null || source == null || message == null
                || module.trim().isEmpty() || source.trim().isEmpty()
                || message.trim().isEmpty()) return;

        String action = "android".equals(context.getPackageName())
                ? ACTION_SYSTEM_EVENT : ACTION_EVENT;
        Intent intent = new Intent(action);
        intent.setPackage(TARGET_PACKAGE);
        intent.putExtra(EXTRA_MODULE, module.trim());
        intent.putExtra(EXTRA_SOURCE, source.trim());
        intent.putExtra(EXTRA_MESSAGE, message.trim());
        context.sendBroadcast(intent);
    }
}
