package com.mt8163.tools.modules.events;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.mt8163.tools.core.EventLogger;

public final class EventsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        accept(context, intent, EventLogger.ACTION_EVENT);
    }

    static void accept(Context context, Intent intent, String expectedAction) {
        if (context == null || intent == null
                || !expectedAction.equals(intent.getAction())) return;

        String module = intent.getStringExtra(EventLogger.EXTRA_MODULE);
        String source = intent.getStringExtra(EventLogger.EXTRA_SOURCE);
        String message = intent.getStringExtra(EventLogger.EXTRA_MESSAGE);
        if (!valid(module, 80) || !valid(source, 80) || !valid(message, 2000)) return;

        EventManager manager = EventManager.get(context);
        if (manager.isEnabled()) manager.log(module, source, message);
    }

    private static boolean valid(String value, int maxLength) {
        return value != null && !value.trim().isEmpty() && value.length() <= maxLength;
    }
}
