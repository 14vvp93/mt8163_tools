package com.mt8163.tools.modules.events;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class EventsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !EventLogger.ACTION_EVENT.equals(intent.getAction())) return;
        EventManager manager = EventManager.get(context);
        if (!manager.isEnabled()) return;
        String module = intent.getStringExtra(EventLogger.EXTRA_MODULE);
        String source = intent.getStringExtra(EventLogger.EXTRA_SOURCE);
        String message = intent.getStringExtra(EventLogger.EXTRA_MESSAGE);
        manager.log(module, source, message);
    }
}
