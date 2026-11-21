package com.mt8163.tools.modules.events;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.mt8163.tools.core.EventLogger;

public final class SystemEventsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        EventsReceiver.accept(context, intent, EventLogger.ACTION_SYSTEM_EVENT);
    }
}
