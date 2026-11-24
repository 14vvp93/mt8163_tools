package com.mt8163.tools.modules.protection;

public final class ProtectionTaskMatcher {
    private static volatile ProtectionConfig configuration = ProtectionConfig.empty();

    private ProtectionTaskMatcher() {
    }

    public static void update(ProtectionConfig config) {
        configuration = config == null ? ProtectionConfig.empty() : config;
    }

    public static boolean shouldKeep(String packageName, int userId) {
        ProtectionConfig.ApplicationSettings settings = settingsFor(packageName, userId);
        return settings != null && settings.shouldKeepRecents();
    }

    public static boolean shouldHide(String packageName, int userId) {
        ProtectionConfig.ApplicationSettings settings = settingsFor(packageName, userId);
        return settings != null && settings.shouldHideRecents();
    }

    public static boolean shouldProtectCleaner(String packageName, int userId) {
        ProtectionConfig.ApplicationSettings settings = settingsFor(packageName, userId);
        return settings != null && settings.shouldProtectCleaner();
    }

    private static ProtectionConfig.ApplicationSettings settingsFor(String packageName,
                                                                     int userId) {
        if (packageName == null || packageName.isEmpty() || userId < 0) return null;
        return configuration.getApplicationSettings(packageName, userId);
    }
}
