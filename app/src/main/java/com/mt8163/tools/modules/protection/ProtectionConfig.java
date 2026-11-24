package com.mt8163.tools.modules.protection;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class ProtectionConfig {
    private final boolean enabled;
    private final Map<String, ApplicationSettings> applications;

    private ProtectionConfig(boolean enabled, Map<String, ApplicationSettings> applications) {
        this.enabled = enabled;
        this.applications = Collections.unmodifiableMap(new HashMap<>(applications));
    }

    public static ProtectionConfig empty() {
        return new ProtectionConfig(false, Collections.emptyMap());
    }

    public static ProtectionConfig fromJson(boolean enabled, String json) {
        Map<String, ApplicationSettings> applications = new HashMap<>();
        if (json == null || json.isEmpty()) {
            return new ProtectionConfig(enabled, applications);
        }

        try {
            JSONObject root = new JSONObject(json);
            Iterator<String> keys = root.keys();

            while (keys.hasNext()) {
                String packageName = keys.next();
                if (!isValidPackageName(packageName)) continue;

                JSONObject item = root.optJSONObject(packageName);
                if (item == null) continue;

                applications.put(packageName, new ApplicationSettings(
                        item.optBoolean("keep_recents", false),
                        item.optBoolean("hide_recents", false),
                        item.optBoolean("protect_cleaner", false)));
            }
        } catch (JSONException ignored) {
            applications.clear();
        }

        return new ProtectionConfig(enabled, applications);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ApplicationSettings getApplicationSettings(String packageName) {
        if (!enabled || !isValidPackageName(packageName)) return null;
        return applications.get(packageName);
    }

    public static String key(String packageName) {
        return packageName;
    }

    public static boolean isValidPackageName(String packageName) {
        return packageName != null
                && packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    }

    public static final class ApplicationSettings {
        private final boolean keepRecents;
        private final boolean hideRecents;
        private final boolean protectCleaner;

        public ApplicationSettings(boolean keepRecents, boolean hideRecents,
                                   boolean protectCleaner) {
            this.hideRecents = hideRecents;
            this.keepRecents = keepRecents && !hideRecents;
            this.protectCleaner = protectCleaner;
        }

        public boolean shouldKeepRecents() { return keepRecents; }
        public boolean shouldHideRecents() { return hideRecents; }
        public boolean shouldProtectCleaner() { return protectCleaner; }
    }
}
