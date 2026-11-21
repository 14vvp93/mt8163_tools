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
    private static final int MAX_MODULE_LENGTH = 80;
    private static final int MAX_SOURCE_LENGTH = 80;
    private static final int MAX_MESSAGE_LENGTH = 2000;

    private EventLogger() {
    }

    public static void log(Context context, String module, String source, String message) {
        if (context == null || !valid(module, MAX_MODULE_LENGTH)
                || !valid(source, MAX_SOURCE_LENGTH)
                || !valid(message, MAX_MESSAGE_LENGTH)) return;

        try {
            String packageName = context.getPackageName();
            String action = "android".equals(packageName)
                    ? ACTION_SYSTEM_EVENT : ACTION_EVENT;
            Intent intent = new Intent(action);
            intent.setPackage(TARGET_PACKAGE);
            intent.putExtra(EXTRA_MODULE, module.trim());
            intent.putExtra(EXTRA_SOURCE, source.trim());
            intent.putExtra(EXTRA_MESSAGE, message.trim());
            context.sendBroadcast(intent);
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean valid(String value, int maxLength) {
        return value != null && !value.trim().isEmpty() && value.length() <= maxLength;
    }
}
