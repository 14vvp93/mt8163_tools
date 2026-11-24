package com.mt8163.tools.xposed.hooks.protection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

import com.mt8163.tools.modules.protection.ProtectionConfig;
import com.mt8163.tools.modules.protection.ProtectionSettingsStore;
import com.mt8163.tools.modules.protection.ProtectionTaskMatcher;
import com.mt8163.tools.xposed.PackageHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class ProtectionHook implements PackageHook {
    private static final String TARGET_PACKAGE = "com.android.systemui";
    private static final String APPLICATION_CLASS =
            "com.android.systemui.SystemUIApplication";
    private static final String APP_PACKAGE = "com.mt8163.tools";
    private static final String SETTINGS_FILE = "protection_hook_config";
    private static final String KEY_ENABLED = "module_enabled";
    private static final String KEY_APPLICATIONS = "applications";
    private static final Object LOCK = new Object();

    private static boolean applicationHookInstalled;
    private static boolean receiverRegistered;
    private static BroadcastReceiver configurationReceiver;

    @Override
    public boolean supports(String packageName) {
        return TARGET_PACKAGE.equals(packageName);
    }

    @Override
    public void install(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        if (param == null || param.classLoader == null
                || !TARGET_PACKAGE.equals(param.packageName)
                || !TARGET_PACKAGE.equals(param.processName)) {
            return;
        }

        synchronized (LOCK) {
            if (applicationHookInstalled) return;

            XposedHelpers.findAndHookMethod(
                    APPLICATION_CLASS,
                    param.classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hookParam) {
                            if (!(hookParam.thisObject instanceof Context)) return;
                            registerConfigurationReceiver((Context) hookParam.thisObject);
                        }
                    });
            applicationHookInstalled = true;
        }

        XposedBridge.log("MT8163 Tools: Protection SystemUI bootstrap hook installed");
    }

    private static void registerConfigurationReceiver(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) return;

        synchronized (LOCK) {
            if (receiverRegistered) return;

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null
                            || !ProtectionSettingsStore.ACTION_CONFIG_CHANGED.equals(
                            intent.getAction())) return;
                    if (!isTrustedSender(receiverContext, getSendingUid())) {
                        XposedBridge.log(
                                "MT8163 Tools: Protection rejected configuration broadcast");
                        return;
                    }
                    loadConfiguration();
                }
            };

            try {
                appContext.registerReceiver(receiver,
                        new IntentFilter(ProtectionSettingsStore.ACTION_CONFIG_CHANGED));
                configurationReceiver = receiver;
                receiverRegistered = true;
            } catch (Throwable error) {
                XposedBridge.log("MT8163 Tools: Protection receiver registration failed: "
                        + error);
                return;
            }
        }

        loadConfiguration();
        XposedBridge.log("MT8163 Tools: Protection configuration receiver registered");
    }

    private static boolean isTrustedSender(Context context, int sendingUid) {
        if (context == null || sendingUid < 0) return false;
        try {
            int appUid = context.getPackageManager().getPackageUid(APP_PACKAGE, 0);
            return sendingUid == appUid;
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return false;
        }
    }

    private static void loadConfiguration() {
        try {
            XSharedPreferences preferences =
                    new XSharedPreferences(APP_PACKAGE, SETTINGS_FILE);
            preferences.reload();

            boolean enabled = preferences.getBoolean(KEY_ENABLED, false);
            String applications = preferences.getString(KEY_APPLICATIONS, "{}");
            ProtectionTaskMatcher.update(ProtectionConfig.fromJson(enabled, applications));
        } catch (Throwable error) {
            XposedBridge.log("MT8163 Tools: Protection configuration load failed: " + error);
            ProtectionTaskMatcher.update(ProtectionConfig.empty());
        }
    }
}
