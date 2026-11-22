package com.mt8163.tools.modules.media;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public final class MediaSettingsStore {
    public static final String ACTION_CONFIG_CHANGED =
            "com.mt8163.tools.action.MEDIA_CONFIG_CHANGED";
    public static final String ACTION_STATE_CHANGED =
            "com.mt8163.tools.action.MEDIA_STATE_CHANGED";
    public static final String EXTRA_ENABLED = "module_enabled";
    public static final String EXTRA_SOURCE_PACKAGE = "source_package";
    public static final String EXTRA_PLAYBACK_STATE = "playback_state";
    public static final String EXTRA_IS_FOREGROUND = "is_foreground";

    public static final String PLAYBACK_PLAYING = "playing";
    public static final String PLAYBACK_PAUSED = "paused";
    public static final String PLAYBACK_UNKNOWN = "unknown";

    private static final String MODULE_SETTINGS = "mt8163_tools";
    private static final String HOOK_SETTINGS = "media_hook_config";
    private static final String STATE_SETTINGS = "media_state";

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

    public static boolean saveSnapshot(Context context, String packageName,
                                       String playbackState, boolean isForeground) {
        if (context == null) return false;

        try {
            return stateSettings(context.getApplicationContext()).edit()
                    .putString(EXTRA_SOURCE_PACKAGE, packageName == null ? "" : packageName)
                    .putString(EXTRA_PLAYBACK_STATE, normalizePlaybackState(playbackState))
                    .putBoolean(EXTRA_IS_FOREGROUND, isForeground)
                    .commit();
        } catch (RuntimeException error) {
            return false;
        }
    }

    public static String normalizePlaybackState(String state) {
        if (state == null) return PLAYBACK_UNKNOWN;

        String value = state.trim();
        if (PLAYBACK_PLAYING.equalsIgnoreCase(value)
                || "start".equalsIgnoreCase(value)
                || "write".equalsIgnoreCase(value)
                || "fast_forwarding".equalsIgnoreCase(value)
                || "rewinding".equalsIgnoreCase(value)) {
            return PLAYBACK_PLAYING;
        }
        if (PLAYBACK_PAUSED.equalsIgnoreCase(value)) return PLAYBACK_PAUSED;
        return PLAYBACK_UNKNOWN;
    }

    public static String getSourcePackage(Context context) {
        return context == null ? "" : stateSettings(context.getApplicationContext())
                .getString(EXTRA_SOURCE_PACKAGE, "");
    }

    public static String getPlaybackState(Context context) {
        return context == null ? "" : stateSettings(context.getApplicationContext())
                .getString(EXTRA_PLAYBACK_STATE, "");
    }

    public static boolean isForeground(Context context) {
        return context != null && stateSettings(context.getApplicationContext())
                .getBoolean(EXTRA_IS_FOREGROUND, false);
    }

    private static SharedPreferences stateSettings(Context context) {
        return context.getSharedPreferences(STATE_SETTINGS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences hookSettings(Context context) {
        try {
            return context.getSharedPreferences(HOOK_SETTINGS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException error) {
            return context.getSharedPreferences(HOOK_SETTINGS, Context.MODE_PRIVATE);
        }
    }
}
