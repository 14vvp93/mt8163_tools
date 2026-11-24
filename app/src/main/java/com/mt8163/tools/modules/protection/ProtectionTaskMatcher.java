package com.mt8163.tools.modules.protection;

public final class ProtectionTaskMatcher {
    private static volatile ProtectionConfig configuration = ProtectionConfig.empty();

    private ProtectionTaskMatcher() {
    }

    public static void update(ProtectionConfig config) {
        configuration = config == null ? ProtectionConfig.empty() : config;
    }

    public static boolean shouldKeep(String packageName) {
        return settingsFor(packageName) != null;
    }

    public static boolean shouldHide(String packageName) {
        ProtectionConfig.ApplicationSettings settings = settingsFor(packageName);
        return settings != null && settings.shouldHideRecents();
    }

    public static boolean shouldProtectCleaner(String packageName) {
        ProtectionConfig.ApplicationSettings settings = settingsFor(packageName);
        return settings != null && settings.shouldProtectCleaner();
    }

    public static boolean shouldAllowCloseViaX(String packageName) {
        ProtectionConfig.ApplicationSettings settings = settingsFor(packageName);
        return settings != null && settings.shouldAllowCloseViaX();
    }

    private static ProtectionConfig.ApplicationSettings settingsFor(String packageName) {
        if (!ProtectionConfig.isValidPackageName(packageName)) return null;
        return configuration.getApplicationSettings(packageName);
    }
}
