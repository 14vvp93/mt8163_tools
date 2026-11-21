package com.mt8163.tools.xposed.hooks.voltage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.UserHandle;

import com.mt8163.tools.core.EventLogger;
import com.mt8163.tools.modules.voltage.VoltageSettingsStore;
import com.mt8163.tools.xposed.PackageHook;

import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class VoltageHook implements PackageHook {
    private static final String TARGET_PACKAGE = "android";
    private static final String TARGET_CLASS = "android.carsource.McuService";
    private static final float MIN_OUTPUT_VOLTAGE = 0.0f;
    private static final float MAX_OUTPUT_VOLTAGE = 25.5f;
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static boolean installAttempted;
    private static boolean hooksInstalled;
    private static boolean hookStatusReported;
    private static boolean receiverRegistered;
    private static boolean bootReceiverRegistered;
    private static boolean bootCompleted;
    private static boolean hasReportedBatteryPacket;
    private static String installError;
    private static String sessionToken;
    private static Context eventContext;
    private static Object mcuService;
    private static BroadcastReceiver configReceiver;
    private static BroadcastReceiver bootReceiver;
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
        synchronized (LOCK) {
            if (installAttempted) {
                if (installError != null) throw new IllegalStateException(installError);
                return;
            }
            installAttempted = true;
        }

        try {
            Class<?> packetClass = XposedHelpers.findClass(
                    TARGET_CLASS + "$McuPacket", param.classLoader);

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
                hooksInstalled = true;
            }
        } catch (Throwable error) {
            synchronized (LOCK) {
                installError = error.toString();
            }
            XposedBridge.log("MT8163 Tools: Voltage hook installation failed: " + error);
            logEvent("hookInstallFailed", "MCU hook installation failed: " + error);
            throw error;
        }
    }

    private static void onServiceCreated(Context context, Object service) {
        synchronized (LOCK) {
            eventContext = context.getApplicationContext();
            mcuService = service;

            if (!hookStatusReported) {
                hookStatusReported = true;
                if (hooksInstalled) {
                    logEvent("hookInstalled", "MCU constructor and onBattery hooks installed");
                } else {
                    logEvent("hookInstallFailed", "MCU hook installation incomplete: "
                            + (installError == null ? "unknown error" : installError));
                }
            }

            if (!receiverRegistered && !registerConfigReceiverLocked(context)) return;

            if (!bootCompleted) {
                bootCompleted = readBootCompleted();
                if (!bootCompleted && !registerBootReceiverLocked(context)) {
                    logEvent("bootReceiverRegistrationFailed",
                            "Unable to wait for Android boot completion");
                    return;
                }
            }
        }

        if (bootCompleted) requestConfiguration(context);
    }

    private static boolean registerConfigReceiverLocked(Context context) {
        try {
            IntentFilter filter = new IntentFilter(
                    VoltageSettingsStore.ACTION_CONFIG_RESPONSE);
            filter.addAction(VoltageSettingsStore.ACTION_CONFIG_CHANGED);

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    receiveConfig(intent);
                }
            };

            context.registerReceiverAsUser(receiver, UserHandle.SYSTEM, filter, null, null);
            configReceiver = receiver;
            sessionToken = createToken();
            receiverRegistered = true;
            configReceived = false;
            logEvent("receiverRegistered", "Voltage configuration receiver registered");
            return true;
        } catch (RuntimeException error) {
            logEvent("receiverRegistrationFailed",
                    "Voltage configuration receiver registration failed: " + error);
            XposedBridge.log("MT8163 Tools: Voltage receiver registration failed: " + error);
            return false;
        }
    }

    private static boolean registerBootReceiverLocked(Context context) {
        if (bootReceiverRegistered) return true;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null
                            || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

                    synchronized (LOCK) {
                        bootCompleted = true;
                        if (bootReceiverRegistered) {
                            try {
                                receiverContext.unregisterReceiver(bootReceiver);
                            } catch (RuntimeException ignored) {
                            }
                            bootReceiver = null;
                            bootReceiverRegistered = false;
                        }
                    }
                    requestConfiguration(receiverContext);
                }
            };

            context.registerReceiverAsUser(receiver, UserHandle.SYSTEM,
                    new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, null);
            bootReceiver = receiver;
            bootReceiverRegistered = true;
            return true;
        } catch (RuntimeException error) {
            XposedBridge.log("MT8163 Tools: Boot receiver registration failed: " + error);
            return false;
        }
    }

    private static boolean readBootCompleted() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method get = properties.getMethod("get", String.class, String.class);
            return "1".equals(get.invoke(null, "sys.boot_completed", "0"));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static void requestConfiguration(Context context) {
        String token;
        synchronized (LOCK) {
            if (!bootCompleted || !receiverRegistered) return;
            token = sessionToken;
        }
        if (!VoltageSettingsStore.isValidToken(token)) return;

        Intent request = new Intent(VoltageSettingsStore.ACTION_CONFIG_REQUEST);
        request.setPackage("com.mt8163.tools");
        request.putExtra(VoltageSettingsStore.EXTRA_TOKEN, token);
        try {
            context.sendBroadcastAsUser(request, UserHandle.SYSTEM);
            logEvent("configurationRequested", "Voltage configuration requested");
        } catch (RuntimeException error) {
            logEvent("configurationRequestFailed",
                    "Voltage configuration request failed: " + error);
        }
    }

    private static String createToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void receiveConfig(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean response = VoltageSettingsStore.ACTION_CONFIG_RESPONSE.equals(action);
        boolean changed = VoltageSettingsStore.ACTION_CONFIG_CHANGED.equals(action);
        if (!response && !changed) return;

        Bundle extras = intent.getExtras();
        if (extras == null) {
            logEvent("invalidConfiguration", "Configuration message has no extras");
            return;
        }

        Object tokenValue = extras.get(VoltageSettingsStore.EXTRA_TOKEN);
        Object enabledValue = extras.get(VoltageSettingsStore.EXTRA_ENABLED);
        Object offsetValue = extras.get(VoltageSettingsStore.EXTRA_OFFSET);
        if (!(tokenValue instanceof String) || !(enabledValue instanceof Boolean)
                || !(offsetValue instanceof Number)) {
            logEvent("invalidConfiguration", "Configuration message has invalid field types");
            return;
        }

        String token = (String) tokenValue;
        float offset = ((Number) offsetValue).floatValue();
        if (!VoltageSettingsStore.isValidToken(token)
                || !VoltageSettingsStore.isValidOffset(offset)) {
            logEvent("invalidConfiguration", "Configuration token or offset is invalid");
            return;
        }

        synchronized (LOCK) {
            if (!sameToken(sessionToken, token)) {
                logEvent("invalidConfiguration", "Configuration session token mismatch");
                return;
            }
            if (changed && !configReceived) return;

            boolean enabled = (Boolean) enabledValue;
            boolean wasEnabled = moduleEnabled;
            float previousOffset = voltageOffset;
            moduleEnabled = enabled;
            voltageOffset = offset;
            if (response) configReceived = true;
            applyCurrentVoltageLocked();

            if (response) {
                logEvent("configurationLoaded", "Voltage configuration received");
            } else if (wasEnabled != enabled || Float.compare(previousOffset, offset) != 0) {
                logEvent("configurationChanged", String.format(Locale.US,
                        "Enabled=%s, offset=%+.1f V", enabled, offset));
            }
        }
    }

    private static boolean sameToken(String expected, String actual) {
        if (!VoltageSettingsStore.isValidToken(expected)
                || !VoltageSettingsStore.isValidToken(actual)) return false;
        return MessageDigest.isEqual(expected.getBytes(), actual.getBytes());
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
            if (!isValidRawVoltage(rawVoltage)) {
                logEvent("voltageInvalid",
                        String.format(Locale.US, "Invalid MCU voltage: %.1f V", rawVoltage));
                return;
            }

            synchronized (LOCK) {
                mcuService = service;
                rawBatteryVoltage = rawVoltage;
                hasRawVoltage = true;
                if (!hasReportedBatteryPacket) {
                    hasReportedBatteryPacket = true;
                    logEvent("batteryPacketReceived", String.format(Locale.US,
                            "MCU battery packet received: subCmd=%d, voltage=%.1f V",
                            subCommand, rawVoltage));
                }
                applyCurrentVoltageLocked();
            }
        } catch (Throwable error) {
            logEvent("hookException", "Battery packet processing failed: " + error);
            XposedBridge.log("MT8163 Tools: Voltage packet processing failed: " + error);
        }
    }

    private static boolean isValidRawVoltage(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value)
                && value > 0.0f && value <= MAX_OUTPUT_VOLTAGE;
    }

    private static void applyCurrentVoltageLocked() {
        if (mcuService == null || !hasRawVoltage || !configReceived) return;

        float requestedVoltage = moduleEnabled
                ? rawBatteryVoltage + voltageOffset : rawBatteryVoltage;
        float outputVoltage = Math.max(MIN_OUTPUT_VOLTAGE,
                Math.min(MAX_OUTPUT_VOLTAGE, requestedVoltage));
        boolean limited = Float.compare(requestedVoltage, outputVoltage) != 0;

        try {
            XposedHelpers.setFloatField(mcuService, "mBatteryVoltage", outputVoltage);
            boolean outputChanged = !hasReportedVoltage
                    || Float.compare(lastReportedVoltage, outputVoltage) != 0;
            lastReportedVoltage = outputVoltage;
            hasReportedVoltage = true;

            if (limited && outputChanged) {
                logEvent("voltageOutputLimited", String.format(Locale.US,
                        "Requested %.1f V, output limited to %.1f V",
                        requestedVoltage, outputVoltage));
            }
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

    private static void logEvent(String source, String message) {
        Context context = eventContext;
        if (context != null) EventLogger.log(context, "Voltage", source, message);
        else XposedBridge.log("MT8163 Tools: Voltage [" + source + "] " + message);
    }
}
