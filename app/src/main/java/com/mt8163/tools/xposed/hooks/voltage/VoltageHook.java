package com.mt8163.tools.xposed.hooks.voltage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import com.mt8163.tools.core.EventLogger;
import com.mt8163.tools.modules.voltage.VoltageSettingsStore;
import com.mt8163.tools.xposed.PackageHook;

import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class VoltageHook implements PackageHook {
    private static final String APP_PACKAGE = "com.mt8163.tools";
    private static final String TARGET_PACKAGE = "android";
    private static final String TARGET_CLASS = "android.carsource.McuService";
    private static final String HOOK_PREFERENCES = "voltage_hook_config";
    private static final String KEY_MODULE_ENABLED = "module_enabled";
    private static final String KEY_VOLTAGE_OFFSET = "voltage_offset";
    private static final Object LOCK = new Object();

    private static boolean installAttempted;
    private static boolean constructorHookInstalled;
    private static boolean batteryHookInstalled;
    private static boolean initialConfigurationLoaded;
    private static boolean receiverRegistered;
    private static boolean hasReportedBatteryPacket;
    private static boolean hasReportedVoltageApplied;
    private static boolean hasReportedVoltage;
    private static float lastReportedVoltage;
    private static Context eventContext;
    private static Object mcuService;
    private static BroadcastReceiver configReceiver;
    private static boolean moduleEnabled;
    private static float voltageOffset;
    private static boolean hasRawVoltage;
    private static float rawBatteryVoltage;

    @Override
    public boolean supports(String packageName) {
        return TARGET_PACKAGE.equals(packageName);
    }

    @Override
    public void install(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        synchronized (LOCK) {
            if (installAttempted) return;
            installAttempted = true;
        }

        loadInitialConfiguration();

        Class<?> packetClass;
        try {
            packetClass = XposedHelpers.findClass(
                    TARGET_CLASS + "$McuPacket", param.classLoader);
        } catch (Throwable error) {
            logInstallFailure("MCU packet class lookup failed", error);
            throw error;
        }

        try {
            XposedHelpers.findAndHookConstructor(
                    TARGET_CLASS,
                    param.classLoader,
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hookParam) {
                            Object contextArg = hookParam.args != null && hookParam.args.length > 0
                                    ? hookParam.args[0] : null;
                            if (contextArg instanceof Context) {
                                onServiceCreated((Context) contextArg, hookParam.thisObject);
                            }
                        }
                    });
            synchronized (LOCK) {
                constructorHookInstalled = true;
            }
        } catch (Throwable error) {
            logInstallFailure("MCU constructor hook failed", error);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    TARGET_CLASS,
                    param.classLoader,
                    "onBattery",
                    packetClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hookParam) {
                            processBatteryPacket(hookParam.thisObject,
                                    hookParam.args != null && hookParam.args.length > 0
                                            ? hookParam.args[0] : null);
                        }
                    });
            synchronized (LOCK) {
                batteryHookInstalled = true;
            }
        } catch (Throwable error) {
            logInstallFailure("MCU onBattery hook failed", error);
        }

        synchronized (LOCK) {
            if (!constructorHookInstalled && !batteryHookInstalled) {
                throw new IllegalStateException("No Voltage hooks were installed");
            }
        }
    }

    private static void onServiceCreated(Context context, Object service) {
        synchronized (LOCK) {
            eventContext = context;
            mcuService = service;
        }

        loadInitialConfiguration();

        synchronized (LOCK) {
            if (!receiverRegistered) registerConfigReceiverLocked(context);
            applyCurrentVoltageLocked();
        }

        logEvent("hookInstalled", "Voltage hooks initialized");
    }

    private static void loadInitialConfiguration() {
        synchronized (LOCK) {
            if (initialConfigurationLoaded) return;
            initialConfigurationLoaded = true;
        }

        try {
            XSharedPreferences preferences =
                    new XSharedPreferences(APP_PACKAGE, HOOK_PREFERENCES);
            preferences.reload();

            boolean enabled = preferences.getBoolean(KEY_MODULE_ENABLED, false);
            float offset = preferences.getFloat(KEY_VOLTAGE_OFFSET, 0.0f);
            if (!VoltageSettingsStore.isValidOffset(offset)) offset = 0.0f;

            synchronized (LOCK) {
                moduleEnabled = enabled;
                voltageOffset = offset;
                applyCurrentVoltageLocked();
            }

            logEvent("configurationLoaded", String.format(Locale.US,
                    "Initial settings loaded: enabled=%s, offset=%+.1f V",
                    enabled, offset));
        } catch (Throwable error) {
            logEvent("configurationLoadFailed",
                    "Unable to read initial Voltage settings: " + error);
            XposedBridge.log("MT8163 Tools: Voltage settings load failed: " + error);
        }
    }

    private static void registerConfigReceiverLocked(Context context) {
        try {
            IntentFilter filter = new IntentFilter(
                    VoltageSettingsStore.ACTION_CONFIG_CHANGED);

            configReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    receiveConfiguration(intent);
                }
            };

            context.registerReceiver(
                    configReceiver, filter, VoltageSettingsStore.CONFIG_PERMISSION, null);
            receiverRegistered = true;
            logEvent("receiverRegistered", "Voltage settings update receiver registered");
        } catch (RuntimeException error) {
            configReceiver = null;
            logEvent("receiverRegistrationFailed",
                    "Voltage settings receiver registration failed: " + error);
            XposedBridge.log("MT8163 Tools: Voltage receiver registration failed: " + error);
        }
    }

    private static void receiveConfiguration(Intent intent) {
        if (intent == null
                || !VoltageSettingsStore.ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
            return;
        }

        Bundle extras = intent.getExtras();
        if (extras == null) return;

        Object enabledValue = extras.get(VoltageSettingsStore.EXTRA_ENABLED);
        Object offsetValue = extras.get(VoltageSettingsStore.EXTRA_OFFSET);
        if (!(enabledValue instanceof Boolean) || !(offsetValue instanceof Number)) {
            logEvent("configurationRejected", "Settings broadcast has invalid fields");
            return;
        }

        boolean enabled = (Boolean) enabledValue;
        float offset = ((Number) offsetValue).floatValue();
        if (!VoltageSettingsStore.isValidOffset(offset)) {
            logEvent("configurationRejected", "Voltage offset is outside the allowed range");
            return;
        }

        synchronized (LOCK) {
            boolean changed = moduleEnabled != enabled
                    || Float.compare(voltageOffset, offset) != 0;
            moduleEnabled = enabled;
            voltageOffset = offset;
            initialConfigurationLoaded = true;
            applyCurrentVoltageLocked();

            if (changed) {
                logEvent("configurationChanged", String.format(Locale.US,
                        "Enabled=%s, offset=%+.1f V", enabled, offset));
            }
        }
    }

    private static void processBatteryPacket(Object service, Object packet) {
        if (service == null || packet == null) return;

        try {
            Object subCommandValue = XposedHelpers.callMethod(packet, "subCmd");
            Object parameterCountValue = XposedHelpers.callMethod(packet, "paramLen");
            if (!(subCommandValue instanceof Number)
                    || !(parameterCountValue instanceof Number)) return;

            int subCommand = ((Number) subCommandValue).intValue();
            int parameterCount = ((Number) parameterCountValue).intValue();
            if (subCommand != 3 || parameterCount < 1) return;

            Object parameterValue = XposedHelpers.callMethod(packet, "param", 0);
            if (!(parameterValue instanceof Number)) return;

            float rawVoltage = (((Number) parameterValue).intValue() & 0xff) / 10.0f;
            if (rawVoltage == 0.0f) {
                synchronized (LOCK) {
                    mcuService = service;
                    hasRawVoltage = false;
                }
                return;
            }
            if (!isValidRawVoltage(rawVoltage)) return;

            synchronized (LOCK) {
                mcuService = service;
                rawBatteryVoltage = rawVoltage;
                hasRawVoltage = true;

                if (!hasReportedBatteryPacket) {
                    hasReportedBatteryPacket = true;
                    logEvent("batteryPacketReceived", String.format(Locale.US,
                            "MCU battery voltage received: %.1f V", rawVoltage));
                }

                applyCurrentVoltageLocked();
            }
        } catch (Throwable error) {
            logEvent("hookException", "Battery packet processing failed: " + error);
            XposedBridge.log("MT8163 Tools: Voltage packet processing failed: " + error);
        }
    }

    private static boolean isValidRawVoltage(float value) {
        return value > 0.0f;
    }

    private static void applyCurrentVoltageLocked() {
        if (mcuService == null || !hasRawVoltage || !moduleEnabled
                || rawBatteryVoltage == 0.0f || voltageOffset == 0.0f) return;

        float outputVoltage = rawBatteryVoltage + voltageOffset;
        if (Float.isNaN(outputVoltage) || Float.isInfinite(outputVoltage)) return;

        try {
            XposedHelpers.setFloatField(mcuService, "mBatteryVoltage", outputVoltage);

            if (!hasReportedVoltageApplied) {
                hasReportedVoltageApplied = true;
                logEvent("voltageApplied", String.format(Locale.US,
                        "mBatteryVoltage updated: %.1f V → %.1f V",
                        rawBatteryVoltage, outputVoltage));
            }

            boolean outputChanged = !hasReportedVoltage
                    || Float.compare(lastReportedVoltage, outputVoltage) != 0;
            lastReportedVoltage = outputVoltage;
            hasReportedVoltage = true;

            if (moduleEnabled && outputChanged
                    && Float.compare(rawBatteryVoltage, outputVoltage) != 0) {
                logEvent("voltageCorrected", String.format(Locale.US,
                        "%.1f V → %.1f V", rawBatteryVoltage, outputVoltage));
            }
        } catch (Throwable error) {
            logEvent("correctionFailed", "MCU voltage field update failed: " + error);
            XposedBridge.log("MT8163 Tools: Voltage field update failed: " + error);
        }
    }

    private static void logInstallFailure(String message, Throwable error) {
        logEvent("hookInstallFailed", message + ": " + error);
        XposedBridge.log("MT8163 Tools: " + message + ": " + error);
    }

    private static void logEvent(String source, String message) {
        Context context = eventContext;
        if (context != null) EventLogger.log(context, "Voltage", source, message);
        else XposedBridge.log("MT8163 Tools: Voltage [" + source + "] " + message);
    }
}
