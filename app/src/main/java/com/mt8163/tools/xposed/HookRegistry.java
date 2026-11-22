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

    private HookRegistry() {
    }

    public static void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (param == null || param.packageName == null) return;

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
