package com.mt8163.tools.xposed;

import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.Set;

public final class SystemBootstrap {
    private static boolean coreServicesHookInstalled;
    private static boolean otherServicesHookInstalled;

    private SystemBootstrap() {
    }

    public static void install(ClassLoader classLoader) {
        Class<?> systemServerClass;
        try {
            systemServerClass = XposedHelpers.findClass(
                    "com.android.server.SystemServer", classLoader);
        } catch (Throwable error) {
            XposedBridge.log("MT8163 Tools: SystemServer class lookup failed: " + error);
            return;
        }

        installStartupHook(systemServerClass, "startCoreServices", true, classLoader);
        installStartupHook(systemServerClass, "startOtherServices", false, classLoader);
    }

    private static void installStartupHook(Class<?> type, String methodName,
                                           boolean coreServices, ClassLoader classLoader) {
        synchronized (SystemBootstrap.class) {
            if (coreServices ? coreServicesHookInstalled : otherServicesHookInstalled) return;
        }

        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    type, methodName, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onSystemServicesStarted(param, classLoader, methodName);
                        }
                    });

            if (hooks == null || hooks.isEmpty()) {
                throw new NoSuchMethodError("SystemServer." + methodName);
            }

            synchronized (SystemBootstrap.class) {
                if (coreServices) {
                    coreServicesHookInstalled = true;
                } else {
                    otherServicesHookInstalled = true;
                }
            }
            XposedBridge.log("MT8163 Tools: SystemServer." + methodName + " hook installed");
        } catch (Throwable error) {
            XposedBridge.log("MT8163 Tools: SystemServer." + methodName
                    + " hook failed: " + error);
        }
    }

    private static void onSystemServicesStarted(MethodHookParam param,
                                                ClassLoader classLoader, String stage) {
        try {
            Object value = XposedHelpers.getObjectField(param.thisObject, "mSystemContext");
            if (!(value instanceof Context)) {
                throw new IllegalStateException("System context is unavailable");
            }
            SystemConfigReceiver.register((Context) value);
        } catch (Throwable error) {
            XposedBridge.log("MT8163 Tools: " + stage
                    + " configuration bootstrap failed: " + error);
        } finally {
            HookRegistry.retryModuleHooks();
        }
    }
}
