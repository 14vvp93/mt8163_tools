package com.mt8163.tools.xposed;

import com.mt8163.tools.xposed.hooks.media.MediaHook;
import com.mt8163.tools.xposed.hooks.protection.ProtectionHook;
import com.mt8163.tools.xposed.hooks.voltage.VoltageHook;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookRegistry {
    private static final PackageHook[] SYSTEM_HOOKS = {
            new MediaHook(),
            new VoltageHook()
    };
    private static final PackageHook[] SYSTEM_UI_HOOKS = {
            new ProtectionHook()
    };
    private static volatile XC_LoadPackage.LoadPackageParam systemParam;

    private HookRegistry() {
    }

    public static void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (param == null || param.packageName == null || param.classLoader == null) return;

        if ("com.android.systemui".equals(param.packageName)) {
            if (!"com.android.systemui".equals(param.processName)) return;
            installModuleHooks(param, SYSTEM_UI_HOOKS);
            return;
        }

        if (!"android".equals(param.packageName)) return;

        systemParam = param;
        installModuleHooks(param, SYSTEM_HOOKS);
        SystemBootstrap.install(param.classLoader);
    }

    public static void retryModuleHooks() {
        XC_LoadPackage.LoadPackageParam param = systemParam;
        if (param != null) installModuleHooks(param, SYSTEM_HOOKS);
    }

    private static void installModuleHooks(XC_LoadPackage.LoadPackageParam param,
                                           PackageHook[] hooks) {
        for (PackageHook hook : hooks) {
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
