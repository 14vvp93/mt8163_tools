package com.mt8163.tools.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class XposedEntry implements IXposedHookLoadPackage {
    private static volatile boolean appProcessActivated;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (param == null || param.packageName == null || param.classLoader == null) {
            return;
        }

        if ("com.mt8163.tools".equals(param.packageName)) {
            appProcessActivated = true;
        }

        HookRegistry.handleLoadPackage(param);
    }

    public static boolean isAppProcessActivated() {
        return appProcessActivated;
    }
}