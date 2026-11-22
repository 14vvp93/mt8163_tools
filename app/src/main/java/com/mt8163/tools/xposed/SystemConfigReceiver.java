package com.mt8163.tools.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.mt8163.tools.modules.media.MediaSettingsStore;
import com.mt8163.tools.modules.voltage.VoltageSettingsStore;
import com.mt8163.tools.xposed.hooks.media.MediaHook;
import com.mt8163.tools.xposed.hooks.voltage.VoltageHook;

import de.robv.android.xposed.XposedBridge;

public final class SystemConfigReceiver {
    private static boolean registered;

    private SystemConfigReceiver() {
    }

    public static void register(Context context) {
        if (context == null) return;

        synchronized (SystemConfigReceiver.class) {
            if (registered) return;

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null) return;

                    String action = intent.getAction();
                    if (MediaSettingsStore.ACTION_CONFIG_CHANGED.equals(action)) {
                        MediaHook.onConfigurationChanged(intent);
                    } else if (VoltageSettingsStore.ACTION_CONFIG_CHANGED.equals(action)) {
                        VoltageHook.onConfigurationChanged(intent);
                    }
                }
            };

            try {
                context.registerReceiver(receiver,
                        new IntentFilter(MediaSettingsStore.ACTION_CONFIG_CHANGED),
                        MediaSettingsStore.CONFIG_PERMISSION, null);
                context.registerReceiver(receiver,
                        new IntentFilter(VoltageSettingsStore.ACTION_CONFIG_CHANGED),
                        VoltageSettingsStore.CONFIG_PERMISSION, null);

                MediaHook.initializeConfiguration(context);
                VoltageHook.initializeConfiguration(context);
                registered = true;
                XposedBridge.log("MT8163 Tools: System configuration receiver registered");
            } catch (Throwable error) {
                try {
                    context.unregisterReceiver(receiver);
                } catch (RuntimeException ignored) {
                }
                XposedBridge.log(
                        "MT8163 Tools: System configuration receiver registration failed: "
                                + error);
            }
        }
    }
}
