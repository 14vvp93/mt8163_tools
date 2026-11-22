package com.mt8163.tools.xposed;

import com.mt8163.tools.xposed.hooks.media.MediaHook;
import com.mt8163.tools.xposed.hooks.voltage.VoltageHook;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookRegistry {
    private static final PackageHook[] HOOKS = {
            new MediaHook(),
            new VoltageHook()
    };
    private static volatile XC_LoadPackage.LoadPackageParam systemParam;

    private HookRegistry() {
    }

    public static void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (param == null || param.packageName == null) return;
        if (!"android".equals(param.packageName)) return;

        systemParam = param;
        installModuleHooks(param);
        SystemBootstrap.install(param.classLoader);
    }

    public static void retryModuleHooks() {
        XC_LoadPackage.LoadPackageParam param = systemParam;
        if (param != null) installModuleHooks(param);
    }

    private static void installModuleHooks(XC_LoadPackage.LoadPackageParam param) {
        for (PackageHook hook : HOOKS) {
            if (hook == null || !hook.supports(param.packageName)) continue;
            try {
                hook.install(param);
            } catch (Throwable error) {
                XposedBridge.log("MT8163 Tools: hook installation failed for "
                        + param.packageName + ": " + error);
            }
        }
    }
}
