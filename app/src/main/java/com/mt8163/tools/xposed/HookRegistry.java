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
    private static volatile XC_LoadPackage.LoadPackageParam systemUiParam;

    private HookRegistry() {
    }

    public static void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (param == null || param.packageName == null || param.classLoader == null) return;

        if ("com.android.systemui".equals(param.packageName)) {
            if (!"com.android.systemui".equals(param.processName)) return;
            systemUiParam = param;
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

    public static void setModuleEnabled(String moduleId, boolean enabled) {
        if (moduleId == null || moduleId.trim().isEmpty()) return;

        PackageHook hook = findHook(moduleId);
        if (hook == null) {
            XposedBridge.log("MT8163 Tools: unknown module lifecycle request: " + moduleId);
            return;
        }

        XC_LoadPackage.LoadPackageParam param = hook.supports("android")
                ? systemParam : systemUiParam;

        if (enabled) {
            if (param == null) return;
            try {
                hook.install(param);
            } catch (Throwable error) {
                XposedBridge.log("MT8163 Tools: failed to enable module "
                        + moduleId + ": " + error);
            }
            return;
        }

        try {
            hook.uninstall();
        } catch (Throwable error) {
            XposedBridge.log("MT8163 Tools: failed to disable module "
                    + moduleId + ": " + error);
        }
    }

    private static PackageHook findHook(String moduleId) {
        for (PackageHook hook : SYSTEM_HOOKS) {
            if (hook != null && moduleId.equals(hook.moduleId())) return hook;
        }
        for (PackageHook hook : SYSTEM_UI_HOOKS) {
            if (hook != null && moduleId.equals(hook.moduleId())) return hook;
        }
        return null;
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
