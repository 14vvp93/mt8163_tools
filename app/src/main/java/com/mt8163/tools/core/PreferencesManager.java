package com.mt8163.tools.core;

import android.content.Context;
import android.content.SharedPreferences;

public final class PreferencesManager {
    private static final String PREFERENCES_NAME = "mt8163_tools";
    private static final String MODULE_ENABLED_KEY_PREFIX = "module_";

    private final SharedPreferences preferences;

    public PreferencesManager(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public boolean getModuleEnabled(String moduleId, boolean defaultEnabled) {
        return preferences.getBoolean(MODULE_ENABLED_KEY_PREFIX + moduleId, defaultEnabled);
    }

    public void setModuleEnabled(String moduleId, boolean enabled) {
        preferences.edit()
                .putBoolean(MODULE_ENABLED_KEY_PREFIX + moduleId, enabled)
                .commit();
    }
}
