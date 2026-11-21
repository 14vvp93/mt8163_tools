package com.mt8163.tools.modules.voltage;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.regex.Pattern;

public final class VoltageSettingsStore {
    public static final String ACTION_CONFIG_REQUEST = "com.mt8163.tools.action.VOLTAGE_CONFIG_REQUEST";
    public static final String ACTION_CONFIG_RESPONSE = "com.mt8163.tools.action.VOLTAGE_CONFIG_RESPONSE";
    public static final String ACTION_CONFIG_CHANGED = "com.mt8163.tools.action.VOLTAGE_CONFIG_CHANGED";
    public static final String EXTRA_TOKEN = "session_token";
    public static final String EXTRA_ENABLED = "module_enabled";
    public static final String EXTRA_OFFSET = "voltage_offset";

    private static final String SETTINGS = "voltage_settings";
    private static final String MODULE_SETTINGS = "mt8163_tools";
    private static final String KEY_OFFSET = "voltage_offset";
    private static final String KEY_TOKEN = "session_token";
    private static final String TARGET_PACKAGE = "android";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private VoltageSettingsStore() {
    }

    public static float getOffset(Context context) {
        float value = settings(context).getFloat(KEY_OFFSET, 0.0f);
        return isValidOffset(value) ? value : 0.0f;
    }

    public static void saveOffset(Context context, float value) {
        if (isValidOffset(value)) {
            settings(context).edit().putFloat(KEY_OFFSET, value).apply();
        }
    }

    public static boolean isModuleEnabled(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(MODULE_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean("module_voltage", false);
    }

    public static void saveSessionToken(Context context, String token) {
        if (isValidToken(token)) {
            settings(context).edit().putString(KEY_TOKEN, token).apply();
        }
    }

    public static void publish(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        String token = settings(app).getString(KEY_TOKEN, null);
        if (!isValidToken(token)) return;

        Intent intent = new Intent(ACTION_CONFIG_CHANGED);
        intent.setPackage(TARGET_PACKAGE);
        intent.putExtra(EXTRA_TOKEN, token);
        intent.putExtra(EXTRA_ENABLED, isModuleEnabled(app));
        intent.putExtra(EXTRA_OFFSET, getOffset(app));
        try {
            app.sendBroadcast(intent);
        } catch (RuntimeException ignored) {
        }
    }

    public static boolean isValidToken(String token) {
        return token != null && TOKEN_PATTERN.matcher(token).matches();
    }

    public static boolean isValidOffset(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value)
                && value >= -2.0f && value <= 2.0f;
    }

    private static SharedPreferences settings(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
    }
}
