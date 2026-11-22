package com.mt8163.tools.modules.voltage;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.mt8163.tools.core.EventLogger;

public final class VoltageSettingsStore {
    public static final String ACTION_CONFIG_CHANGED =
            "com.mt8163.tools.action.VOLTAGE_CONFIG_CHANGED";
    public static final String CONFIG_PERMISSION =
            "com.mt8163.tools.permission.UPDATE_VOLTAGE_CONFIG";
    public static final String EXTRA_ENABLED = "module_enabled";
    public static final String EXTRA_OFFSET = "voltage_offset";

    private static final String SETTINGS = "voltage_settings";
    private static final String MODULE_SETTINGS = "mt8163_tools";
    private static final String HOOK_SETTINGS = "voltage_hook_config";
    private static final String KEY_OFFSET = "voltage_offset";
    private static final String KEY_ENABLED = "module_voltage";
    private static final String KEY_HOOK_ENABLED = "module_enabled";
    private static final String KEY_HOOK_OFFSET = "voltage_offset";
    private static final String TARGET_PACKAGE = "android";

    private VoltageSettingsStore() {
    }

    public static float getOffset(Context context) {
        float value = settings(context).getFloat(KEY_OFFSET, 0.0f);
        return isValidOffset(value) ? value : 0.0f;
    }

    public static void saveOffset(Context context, float value) {
        if (isValidOffset(value)) {
            settings(context).edit().putFloat(KEY_OFFSET, value).commit();
        }
    }

    public static boolean isModuleEnabled(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(MODULE_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    public static void publish(Context context) {
        if (context == null) return;

        Context app = context.getApplicationContext();
        boolean enabled = isModuleEnabled(app);
        float offset = getOffset(app);

        boolean saved = hookSettings(app).edit()
                .putBoolean(KEY_HOOK_ENABLED, enabled)
                .putFloat(KEY_HOOK_OFFSET, offset)
                .commit();
        if (!saved) {
            EventLogger.log(app, "Voltage", "configurationSave",
                    "Failed to persist hook settings");
        }

        Intent intent = new Intent(ACTION_CONFIG_CHANGED);
        intent.setPackage(TARGET_PACKAGE);
        intent.putExtra(EXTRA_ENABLED, enabled);
        intent.putExtra(EXTRA_OFFSET, offset);

        try {
            app.sendBroadcast(intent);
        } catch (RuntimeException error) {
            String message = "Failed to send configuration broadcast: "
                    + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage());
            Log.e("MT8163Tools", message, error);
            EventLogger.log(app, "Voltage", "configurationBroadcast", message);
        }
    }

    public static boolean isValidOffset(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value)
                && value >= -2.0f && value <= 2.0f;
    }

    private static SharedPreferences settings(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences hookSettings(Context context) {
        try {
            return context.getSharedPreferences(
                    HOOK_SETTINGS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException ignored) {
            return context.getSharedPreferences(HOOK_SETTINGS, Context.MODE_PRIVATE);
        }
    }
}
