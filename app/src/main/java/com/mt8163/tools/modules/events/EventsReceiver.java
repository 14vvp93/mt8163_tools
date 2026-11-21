package com.mt8163.tools.modules.events;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Process;

import com.mt8163.tools.core.EventLogger;

public final class EventsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !EventLogger.ACTION_EVENT.equals(intent.getAction())) return;

        int senderUid = Binder.getCallingUid();
        int appUid = context.getApplicationInfo().uid;
        if (senderUid != Process.SYSTEM_UID && senderUid != appUid) return;

        EventManager manager = EventManager.get(context);
        if (!manager.isEnabled()) return;

        String module = intent.getStringExtra(EventLogger.EXTRA_MODULE);
        String source = intent.getStringExtra(EventLogger.EXTRA_SOURCE);
        String message = intent.getStringExtra(EventLogger.EXTRA_MESSAGE);
        manager.log(module, source, message);
    }
}
