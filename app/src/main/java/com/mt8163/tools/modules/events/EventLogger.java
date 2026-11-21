package com.mt8163.tools.modules.events;

import android.content.Context;
import android.content.Intent;

public final class EventLogger {
    public static final String ACTION_EVENT = "com.mt8163.tools.action.EVENT";
    public static final String EXTRA_MODULE = "event_module";
    public static final String EXTRA_SOURCE = "event_source";
    public static final String EXTRA_MESSAGE = "event_message";
    private static final String TARGET_PACKAGE = "com.mt8163.tools";

    private EventLogger() {
    }

    public static void log(Context context, String module, String source, String message) {
        if (context == null || module == null || source == null || message == null) return;
        Intent intent = new Intent(ACTION_EVENT);
        intent.setPackage(TARGET_PACKAGE);
        intent.putExtra(EXTRA_MODULE, module);
        intent.putExtra(EXTRA_SOURCE, source);
        intent.putExtra(EXTRA_MESSAGE, message);
        context.sendBroadcast(intent);
    }
}
