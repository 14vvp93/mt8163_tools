package com.mt8163.tools.modules.events;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.mt8163.tools.core.EventLogger;

public final class EventsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        accept(context, intent, EventLogger.ACTION_EVENT);
    }

    static void accept(Context context, Intent intent, String expectedAction) {
        if (context == null || intent == null
                || !expectedAction.equals(intent.getAction())) return;

        Bundle extras = intent.getExtras();
        if (extras == null) return;

        Object moduleValue = extras.get(EventLogger.EXTRA_MODULE);
        Object sourceValue = extras.get(EventLogger.EXTRA_SOURCE);
        Object messageValue = extras.get(EventLogger.EXTRA_MESSAGE);
        if (!(moduleValue instanceof String) || !(sourceValue instanceof String)
                || !(messageValue instanceof String)) return;

        String module = (String) moduleValue;
        String source = (String) sourceValue;
        String message = (String) messageValue;
        if (!valid(module, 80) || !valid(source, 80) || !valid(message, 2000)) return;

        EventManager manager = EventManager.get(context);
        if (manager.isEnabled()) manager.log(module.trim(), source.trim(), message.trim());
    }

    private static boolean valid(String value, int maxLength) {
        return value != null && !value.trim().isEmpty() && value.length() <= maxLength;
    }
}
