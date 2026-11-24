package com.mt8163.tools.modules.protection;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.mt8163.tools.core.EventLogger;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class ProtectionSettingsStore {
    public static final String ACTION_CONFIG_CHANGED =
            "com.mt8163.tools.action.PROTECTION_CONFIG_CHANGED";

    private static final String TARGET_PACKAGE = "com.android.systemui";
    private static final String MODULE_SETTINGS = "mt8163_tools";
    private static final String SETTINGS = "protection_settings";
    private static final String HOOK_SETTINGS = "protection_hook_config";
    private static final String KEY_ENABLED = "module_enabled";
    private static final String KEY_APPLICATIONS = "applications";

    private ProtectionSettingsStore() {
    }

    public static List<String> getConfiguredPackageNames(Context context) {
        List<String> result = new ArrayList<>();
        if (context == null) return result;

        try {
            String json = context.getApplicationContext()
                    .getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
                    .getString(KEY_APPLICATIONS, "{}");
            JSONObject applications = new JSONObject(json);
            Iterator<String> keys = applications.keys();
            while (keys.hasNext()) {
                String packageName = keys.next();
                if (ProtectionConfig.isValidPackageName(packageName)
                        && applications.optJSONObject(packageName) != null) {
                    result.add(packageName);
                }
            }
        } catch (JSONException | RuntimeException error) {
            EventLogger.log(context, "Protection", "configurationRead",
                    "Failed to read configured applications: " + error);
        }
        return result;
    }

    public static ProtectionConfig.ApplicationSettings getApplicationSettings(
            Context context, String packageName) {
        if (context == null || !ProtectionConfig.isValidPackageName(packageName)) {
            return new ProtectionConfig.ApplicationSettings(false, false, false);
        }

        try {
            String json = context.getApplicationContext()
                    .getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
                    .getString(KEY_APPLICATIONS, "{}");
            JSONObject applications = new JSONObject(json);
            JSONObject item = applications.optJSONObject(ProtectionConfig.key(packageName));
            if (item == null) {
                return new ProtectionConfig.ApplicationSettings(false, false, false);
            }
            return new ProtectionConfig.ApplicationSettings(
                    item.getBoolean("hide_recents"),
                    item.getBoolean("protect_cleaner"),
                    item.getBoolean("allow_close_via_x"));
        } catch (JSONException | RuntimeException error) {
            EventLogger.log(context, "Protection", "configurationRead",
                    "Failed to read application settings: " + error);
            return new ProtectionConfig.ApplicationSettings(false, false, false, false);
        }
    }

    public static boolean setApplicationSettings(Context context, String packageName,
                                                 boolean hideRecents, boolean protectCleaner,
                                                 boolean allowCloseViaX) {
        if (context == null || !ProtectionConfig.isValidPackageName(packageName)) return false;

        Context app = context.getApplicationContext();
        SharedPreferences preferences = app.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);

        try {
            JSONObject applications = new JSONObject(
                    preferences.getString(KEY_APPLICATIONS, "{}"));
            JSONObject settings = new JSONObject();
            settings.put("hide_recents", hideRecents);
            settings.put("protect_cleaner", protectCleaner);
            settings.put("allow_close_via_x", allowCloseViaX);
            applications.put(ProtectionConfig.key(packageName), settings);

            if (!preferences.edit().putString(KEY_APPLICATIONS, applications.toString())
                    .commit()) return false;
            publish(app);
            return true;
        } catch (JSONException | RuntimeException error) {
            EventLogger.log(app, "Protection", "configurationSave",
                    "Failed to save application settings: " + error);
            return false;
        }
    }

    public static boolean removeApplicationSettings(Context context, String packageName) {
        if (context == null || !ProtectionConfig.isValidPackageName(packageName)) return false;

        Context app = context.getApplicationContext();
        SharedPreferences preferences = app.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);

        try {
            JSONObject applications = new JSONObject(
                    preferences.getString(KEY_APPLICATIONS, "{}"));
            applications.remove(ProtectionConfig.key(packageName));
            if (!preferences.edit().putString(KEY_APPLICATIONS, applications.toString())
                    .commit()) return false;
            publish(app);
            return true;
        } catch (JSONException | RuntimeException error) {
            EventLogger.log(app, "Protection", "configurationSave",
                    "Failed to remove application settings: " + error);
            return false;
        }
    }

    public static void publish(Context context) {
        if (context == null) return;

        Context app = context.getApplicationContext();
        boolean enabled = app.getSharedPreferences(MODULE_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean("module_protection", false);
        String applications = app.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
                .getString(KEY_APPLICATIONS, "{}");

        boolean saved = hookSettings(app).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_APPLICATIONS, applications)
                .commit();
        if (!saved) {
            EventLogger.log(app, "Protection", "configurationSave",
                    "Failed to persist hook configuration");
            return;
        }

        Intent intent = new Intent(ACTION_CONFIG_CHANGED);
        intent.setPackage(TARGET_PACKAGE);
        try {
            app.sendBroadcast(intent);
        } catch (RuntimeException error) {
            EventLogger.log(app, "Protection", "configurationBroadcast",
                    "Failed to send configuration broadcast: " + error);
        }
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences hookSettings(Context context) {
        try {
            return context.getSharedPreferences(HOOK_SETTINGS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException error) {
            return context.getSharedPreferences(HOOK_SETTINGS, Context.MODE_PRIVATE);
        }
    }
}