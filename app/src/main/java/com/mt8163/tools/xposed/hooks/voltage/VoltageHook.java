package com.mt8163.tools.xposed.hooks.voltage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.mt8163.tools.core.EventLogger;
import com.mt8163.tools.modules.voltage.VoltageSettingsStore;
import com.mt8163.tools.xposed.PackageHook;

import java.security.SecureRandom;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class VoltageHook implements PackageHook {
    private static final String TARGET_PACKAGE = "android";
    private static final String TARGET_CLASS = "android.carsource.McuService";
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static String sessionToken;
    private static Context eventContext;
    private static Object mcuService;
    private static boolean receiverRegistered;
    private static boolean hookReported;
    private static boolean configReceived;
    private static boolean moduleEnabled;
    private static float voltageOffset;
    private static boolean hasRawVoltage;
    private static float rawBatteryVoltage;
    private static boolean hasReportedVoltage;
    private static float lastReportedVoltage;

    @Override
    public boolean supports(String packageName) {
        return TARGET_PACKAGE.equals(packageName);
    }

    @Override
    public void install(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        Class<?> packetClass = XposedHelpers.findClass(
                TARGET_CLASS + "$McuPacket", param.classLoader);

        XposedHelpers.findAndHookConstructor(
                TARGET_CLASS,
                param.classLoader,
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam hookParam) {
                        registerReceiverAndRequestConfig(
                                (Context) hookParam.args[0], hookParam.thisObject);
                    }
                });

        XposedHelpers.findAndHookMethod(
                TARGET_CLASS,
                param.classLoader,
                "onBattery",
                packetClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam hookParam) {
                        processBatteryPacket(hookParam.thisObject, hookParam.args[0]);
                    }
                });
    }

    private static void registerReceiverAndRequestConfig(Context context, Object service) {
        synchronized (LOCK) {
            eventContext = context.getApplicationContext();
            mcuService = service;
            if (receiverRegistered) return;

            try {
                IntentFilter filter = new IntentFilter(
                        VoltageSettingsStore.ACTION_CONFIG_RESPONSE);
                filter.addAction(VoltageSettingsStore.ACTION_CONFIG_CHANGED);
                context.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context receiverContext, Intent intent) {
                        receiveConfig(intent);
                    }
                }, filter);

                sessionToken = createToken();
                configReceived = false;
                receiverRegistered = true;
                if (!hookReported) {
                    logEvent("targetClassFound", TARGET_CLASS);
                    logEvent("targetMethodFound", "onBattery(McuPacket)");
                    logEvent("hookInstalled", "MCU battery hook installed");
                    hookReported = true;
                }
            } catch (Throwable error) {
                logEvent("hookFailed", "Config receiver registration failed: " + error);
                XposedBridge.log("MT8163 Tools: Voltage receiver registration failed: " + error);
                return;
            }
        }

        Intent request = new Intent(VoltageSettingsStore.ACTION_CONFIG_REQUEST);
        request.setPackage("com.mt8163.tools");
        request.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        request.putExtra(VoltageSettingsStore.EXTRA_TOKEN, getSessionToken());
        context.sendBroadcast(request);
    }

    private static String getSessionToken() {
        synchronized (LOCK) {
            return sessionToken;
        }
    }

    private static String createToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void receiveConfig(Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        boolean response = VoltageSettingsStore.ACTION_CONFIG_RESPONSE.equals(action);
        if (!response && !VoltageSettingsStore.ACTION_CONFIG_CHANGED.equals(action)) return;
        if (!intent.hasExtra(VoltageSettingsStore.EXTRA_ENABLED)
                || !intent.hasExtra(VoltageSettingsStore.EXTRA_OFFSET)) return;

        String token = intent.getStringExtra(VoltageSettingsStore.EXTRA_TOKEN);
        boolean enabled = intent.getBooleanExtra(VoltageSettingsStore.EXTRA_ENABLED, false);
        float offset = intent.getFloatExtra(VoltageSettingsStore.EXTRA_OFFSET, Float.NaN);
        if (!VoltageSettingsStore.isValidOffset(offset)) {
            logEvent("invalidConfiguration", "Received invalid voltage offset");
            return;
        }

        synchronized (LOCK) {
            if (sessionToken == null || !sessionToken.equals(token)) return;
            if (!response && !configReceived) return;

            boolean wasEnabled = moduleEnabled;
            float previousOffset = voltageOffset;
            moduleEnabled = enabled;
            voltageOffset = offset;
            if (response) configReceived = true;
            applyCurrentVoltageLocked();

            if (response) {
                logEvent("configurationLoaded", "Voltage configuration received");
            } else if (wasEnabled != enabled || Float.compare(previousOffset, offset) != 0) {
                logEvent("configurationChanged",
                        String.format(Locale.US, "Enabled=%s, offset=%+.1f V", enabled, offset));
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
            if (subCommand != 3) return;
            if (parameterCount < 1) {
                logEvent("voltageInvalid", "Battery packet has no voltage parameter");
                return;
            }

            Object parameterValue = XposedHelpers.callMethod(packet, "param", 0);
            if (!(parameterValue instanceof Number)) {
                logEvent("voltageInvalid", "Battery voltage parameter is not numeric");
                return;
            }

            int parameter = ((Number) parameterValue).intValue();
            float rawVoltage = (parameter & 0xff) / 10.0f;
            if (rawVoltage <= 0.0f || rawVoltage > 25.5f) {
                logEvent("voltageInvalid",
                        String.format(Locale.US, "Invalid MCU voltage: %.1f V", rawVoltage));
                return;
            }

            synchronized (LOCK) {
                mcuService = service;
                rawBatteryVoltage = rawVoltage;
                hasRawVoltage = true;
                applyCurrentVoltageLocked();
            }
        } catch (Throwable error) {
            logEvent("hookException", "Battery packet processing failed: " + error);
            XposedBridge.log("MT8163 Tools: Voltage packet processing failed: " + error);
        }
    }

    private static void applyCurrentVoltageLocked() {
        if (mcuService == null || !hasRawVoltage || !configReceived) return;

        float effectiveVoltage = moduleEnabled
                ? rawBatteryVoltage + voltageOffset
                : rawBatteryVoltage;
        try {
            XposedHelpers.setFloatField(mcuService, "mBatteryVoltage", effectiveVoltage);
            boolean outputChanged = !hasReportedVoltage
                    || Float.compare(lastReportedVoltage, effectiveVoltage) != 0;
            lastReportedVoltage = effectiveVoltage;
            hasReportedVoltage = true;
            if (moduleEnabled && outputChanged
                    && Float.compare(rawBatteryVoltage, effectiveVoltage) != 0) {
                logEvent("voltageCorrected", String.format(Locale.US,
                        "%.1f V → %.1f V", rawBatteryVoltage, effectiveVoltage));
            }
        } catch (Throwable error) {
            logEvent("correctionFailed", "MCU voltage field update failed: " + error);
            XposedBridge.log("MT8163 Tools: Voltage field update failed: " + error);
        }
    }

    private static void logEvent(String source, String message) {
        Context context = eventContext;
        if (context != null) EventLogger.log(context, "Voltage", source, message);
    }
}
