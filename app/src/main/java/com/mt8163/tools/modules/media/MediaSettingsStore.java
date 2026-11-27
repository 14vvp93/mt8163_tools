package com.mt8163.tools.modules.media;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public final class MediaSettingsStore {
    public static final String ACTION_CONFIG_CHANGED =
            "com.mt8163.tools.action.MEDIA_CONFIG_CHANGED";
    public static final String CONFIG_PERMISSION =
            "com.mt8163.tools.permission.UPDATE_MEDIA_CONFIG";
    public static final String ACTION_STATE_CHANGED =
            "com.mt8163.tools.action.MEDIA_STATE_CHANGED";
    public static final String ACTION_RESTORE_REQUEST =
            "com.mt8163.tools.action.MEDIA_RESTORE_REQUEST";
    public static final String ACTION_SNAPSHOT_UPDATED =
            "com.mt8163.tools.action.MEDIA_SNAPSHOT_UPDATED";
    public static final String EXTRA_ENABLED = "module_enabled";
    public static final String EXTRA_RESTORE_BACKGROUND_MEDIA = "restore_background_media";
    public static final String EXTRA_RESTORE_BACKGROUND_BLUETOOTH =
            "restore_background_bluetooth";
    public static final String EXTRA_PLAYBACK_NOTIFICATIONS = "playback_notifications";
    public static final String EXTRA_SOURCE_PACKAGE = "source_package";
    public static final String EXTRA_PLAYBACK_STATE = "playback_state";
    public static final String EXTRA_IS_FOREGROUND = "is_foreground";

    public static final String PLAYBACK_PLAYING = "playing";
    public static final String PLAYBACK_PAUSED = "paused";
    public static final String PLAYBACK_UNKNOWN = "unknown";

    private static final String BLUETOOTH_SOURCE_PACKAGE = "com.autochips.bluetooth";
    private static final String MODULE_SETTINGS = "mt8163_tools";
    private static final String HOOK_SETTINGS = "media_hook_config";
    private static final String STATE_SETTINGS = "media_state";

    private MediaSettingsStore() {
    }

    public static boolean publish(Context context) {
        if (context == null) return false;

        Context app = context.getApplicationContext();
        SharedPreferences modulePreferences =
                app.getSharedPreferences(MODULE_SETTINGS, Context.MODE_PRIVATE);
        boolean enabled = modulePreferences.getBoolean("module_media", false);
        boolean restoreMedia = modulePreferences.getBoolean(
                EXTRA_RESTORE_BACKGROUND_MEDIA, false);
        boolean restoreBluetooth = modulePreferences.getBoolean(
                EXTRA_RESTORE_BACKGROUND_BLUETOOTH, false);
        boolean playbackNotifications = modulePreferences.getBoolean(
                EXTRA_PLAYBACK_NOTIFICATIONS, false);

        boolean saved = hookSettings(app).edit()
                .putBoolean(EXTRA_ENABLED, enabled)
                .putBoolean(EXTRA_RESTORE_BACKGROUND_MEDIA, restoreMedia)
                .putBoolean(EXTRA_RESTORE_BACKGROUND_BLUETOOTH, restoreBluetooth)
                .putBoolean(EXTRA_PLAYBACK_NOTIFICATIONS, playbackNotifications)
                .commit();
        if (!saved) {
            try {
                com.mt8163.tools.core.EventLogger.log(
                        app, "Media", "configurationSave", "Failed to persist hook settings");
            } catch (RuntimeException ignored) {
            }
            return false;
        }

        Intent intent = new Intent(ACTION_CONFIG_CHANGED);
        intent.setPackage("android");
        intent.putExtra(EXTRA_ENABLED, enabled);
        intent.putExtra(EXTRA_RESTORE_BACKGROUND_MEDIA, restoreMedia);
        intent.putExtra(EXTRA_RESTORE_BACKGROUND_BLUETOOTH, restoreBluetooth);
        intent.putExtra(EXTRA_PLAYBACK_NOTIFICATIONS, playbackNotifications);
        try {
            app.sendBroadcast(intent);
            return true;
        } catch (RuntimeException error) {
            String message = "Failed to send configuration broadcast: "
                    + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage());
            try {
                com.mt8163.tools.core.EventLogger.log(
                        app, "Media", "configurationBroadcast", message);
            } catch (RuntimeException ignored) {
            }
            return false;
        }
    }

    public static boolean isEnabled(Context context) {
        return context != null && context.getApplicationContext()
                .getSharedPreferences(MODULE_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean("module_media", false);
    }

    public static boolean isRestoreBackgroundMedia(Context context) {
        return context != null && context.getApplicationContext()
                .getSharedPreferences(MODULE_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(EXTRA_RESTORE_BACKGROUND_MEDIA, false);
    }

    public static boolean isRestoreBackgroundBluetooth(Context context) {
        return context != null && context.getApplicationContext()
                .getSharedPreferences(MODULE_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(EXTRA_RESTORE_BACKGROUND_BLUETOOTH, false);
    }

    public static boolean isPlaybackNotifications(Context context) {
        return context != null && context.getApplicationContext()
                .getSharedPreferences(MODULE_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(EXTRA_PLAYBACK_NOTIFICATIONS, false);
    }

    public static boolean setPlaybackNotifications(Context context, boolean enabled) {
        if (context == null) return false;
        boolean saved = context.getApplicationContext()
                .getSharedPreferences(MODULE_SETTINGS, Context.MODE_PRIVATE)
                .edit().putBoolean(EXTRA_PLAYBACK_NOTIFICATIONS, enabled).commit();
        if (!saved) {
            logConfigurationSaveFailure(context, EXTRA_PLAYBACK_NOTIFICATIONS);
            return false;
        }
        return publish(context);
    }

    public static boolean setRestoreBackgroundMedia(Context context, boolean enabled) {
        if (context == null) return false;
        boolean saved = context.getApplicationContext()
                .getSharedPreferences(MODULE_SETTINGS, Context.MODE_PRIVATE)
                .edit().putBoolean(EXTRA_RESTORE_BACKGROUND_MEDIA, enabled).commit();
        if (!saved) {
            logConfigurationSaveFailure(context, EXTRA_RESTORE_BACKGROUND_MEDIA);
            return false;
        }
        return publish(context);
    }

    public static boolean setRestoreBackgroundBluetooth(Context context, boolean enabled) {
        if (context == null) return false;
        boolean saved = context.getApplicationContext()
                .getSharedPreferences(MODULE_SETTINGS, Context.MODE_PRIVATE)
                .edit().putBoolean(EXTRA_RESTORE_BACKGROUND_BLUETOOTH, enabled).commit();
        if (!saved) {
            logConfigurationSaveFailure(context, EXTRA_RESTORE_BACKGROUND_BLUETOOTH);
            return false;
        }
        return publish(context);
    }

    private static void logConfigurationSaveFailure(Context context, String setting) {
        try {
            com.mt8163.tools.core.EventLogger.log(
                    context.getApplicationContext(), "Media",
                    "configurationSave", "Failed to persist " + setting);
        } catch (RuntimeException ignored) {
        }
    }

    public static boolean saveSnapshot(Context context, String packageName,
                                       String playbackState, boolean isForeground) {
        if (context == null) return false;

        String source = packageName == null ? "" : packageName.trim();
        String state = normalizePlaybackState(playbackState);

        try {
            SharedPreferences.Editor editor =
                    stateSettings(context.getApplicationContext()).edit()
                            .putString(EXTRA_SOURCE_PACKAGE, source)
                            .putString(EXTRA_PLAYBACK_STATE, state)
                            .putBoolean(EXTRA_IS_FOREGROUND, isForeground);

            editor.remove("media_snapshot_saved")
                    .remove("media_source_package")
                    .remove("media_playback_state")
                    .remove("media_is_foreground")
                    .remove("bluetooth_snapshot_saved")
                    .remove("bluetooth_source_package")
                    .remove("bluetooth_playback_state")
                    .remove("bluetooth_is_foreground");

            boolean saved = editor.commit();
            if (saved) {
                Intent updated = new Intent(ACTION_SNAPSHOT_UPDATED);
                updated.setPackage(context.getApplicationContext().getPackageName());
                context.getApplicationContext().sendBroadcast(updated);
            }
            return saved;
        } catch (RuntimeException error) {
            return false;
        }
    }

    public static String getSourcePackage(Context context) {
        return context == null ? "" : stateSettings(context.getApplicationContext())
                .getString(EXTRA_SOURCE_PACKAGE, "");
    }

    public static String getPlaybackState(Context context) {
        return context == null ? "" : stateSettings(context.getApplicationContext())
                .getString(EXTRA_PLAYBACK_STATE, PLAYBACK_UNKNOWN);
    }

    public static boolean isForeground(Context context) {
        return context != null && stateSettings(context.getApplicationContext())
                .getBoolean(EXTRA_IS_FOREGROUND, false);
    }

    public static boolean isBluetoothSource(Context context) {
        return BLUETOOTH_SOURCE_PACKAGE.equals(getSourcePackage(context));
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