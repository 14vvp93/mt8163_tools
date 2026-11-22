package com.mt8163.tools.modules.media;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public final class MediaSettingsStore {
    public static final String ACTION_CONFIG_CHANGED =
            "com.mt8163.tools.action.MEDIA_CONFIG_CHANGED";
    public static final String ACTION_REQUEST_SNAPSHOT =
            "com.mt8163.tools.action.MEDIA_REQUEST_SNAPSHOT";
    public static final String ACTION_STATE_CHANGED =
            "com.mt8163.tools.action.MEDIA_STATE_CHANGED";
    public static final String EXTRA_ENABLED = "module_enabled";
    public static final String EXTRA_SOURCE_PACKAGE = "source_package";
    public static final String EXTRA_PLAYBACK_STATE = "playback_state";

    private static final String MODULE_SETTINGS = "mt8163_tools";
    private static final String HOOK_SETTINGS = "media_hook_config";

    private MediaSettingsStore() {
    }

    public static void publish(Context context) {
        if (context == null) return;

        Context app = context.getApplicationContext();
        boolean enabled = app.getSharedPreferences(MODULE_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean("module_media", false);

        hookSettings(app).edit().putBoolean(EXTRA_ENABLED, enabled).apply();

        Intent intent = new Intent(ACTION_CONFIG_CHANGED);
        intent.setPackage("android");
        intent.putExtra(EXTRA_ENABLED, enabled);
        app.sendBroadcast(intent);
    }

    public static void requestSnapshot(Context context) {
        if (context == null) return;

        Intent intent = new Intent(ACTION_REQUEST_SNAPSHOT);
        intent.setPackage("android");
        context.getApplicationContext().sendBroadcast(intent);
    }

    private static SharedPreferences hookSettings(Context context) {
        try {
            return context.getSharedPreferences(HOOK_SETTINGS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException error) {
            return context.getSharedPreferences(HOOK_SETTINGS, Context.MODE_PRIVATE);
        }
    }
}
