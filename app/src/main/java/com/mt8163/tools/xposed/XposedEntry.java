package com.mt8163.tools.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class XposedEntry implements IXposedHookLoadPackage {
    private static final String ACTIVATION_PROPERTY = "com.mt8163.tools.xposed.app_activated";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (param == null || param.packageName == null || param.classLoader == null) {
            return;
        }

        if ("com.mt8163.tools".equals(param.packageName)) {
            System.setProperty(ACTIVATION_PROPERTY, "true");
        }

        HookRegistry.handleLoadPackage(param);
    }

    public static boolean isAppProcessActivated() {
        return "true".equals(System.getProperty(ACTIVATION_PROPERTY, "false"));
    }
}