package com.mt8163.tools.xposed;

import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class SystemBootstrap {
    private static boolean installed;

    private SystemBootstrap() {
    }

    public static void install(ClassLoader classLoader) {
        synchronized (SystemBootstrap.class) {
            if (installed) return;
            installed = true;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.SystemServer",
                    classLoader,
                    "startBootstrapServices",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object value = XposedHelpers.getObjectField(
                                        param.thisObject, "mSystemContext");
                                if (!(value instanceof Context)) {
                                    throw new IllegalStateException(
                                            "System context is unavailable after bootstrap");
                                }
                                SystemConfigReceiver.register((Context) value);
                            } catch (Throwable error) {
                                XposedBridge.log(
                                        "MT8163 Tools: System config bootstrap failed: " + error);
                            }
                        }
                    });
        } catch (Throwable error) {
            synchronized (SystemBootstrap.class) {
                installed = false;
            }
            XposedBridge.log("MT8163 Tools: System bootstrap hook failed: " + error);
        }
    }
}
