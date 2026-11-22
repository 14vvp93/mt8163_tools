package com.mt8163.tools.xposed.hooks.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.mt8163.tools.core.EventLogger;
import com.mt8163.tools.modules.media.BluetoothMusicClient;
import com.mt8163.tools.xposed.PackageHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MediaHook implements PackageHook {
    private static final String APP_PACKAGE = "com.mt8163.tools";
    private static final String SOURCE_INFO_CLASS = "android.sourceservice.SourceInfo";
    private static final String BLUETOOTH_SOURCE_PACKAGE = "com.autochips.bluetooth";
    private static final String ACC_RECEIVER_CLASS = "android.carsource.McuService$1";
    private static final String WINDOW_MANAGER_CLASS =
            "com.android.server.wm.WindowManagerService";
    private static final String ACC_ACTION = "android.mcu.device.action.ACC";
    private static final String ACC_EXTRA = "AccStatus";
    private static final String CONFIG_FILE = "media_hook_config";
    private static final String ACTION_CONFIG_CHANGED =
            "com.mt8163.tools.action.MEDIA_CONFIG_CHANGED";
    private static final String ACTION_STATE_CHANGED =
            "com.mt8163.tools.action.MEDIA_STATE_CHANGED";
    private static final String EXTRA_ENABLED = "module_enabled";
    private static final String EXTRA_SOURCE_PACKAGE = "source_package";
    private static final String EXTRA_PLAYBACK_STATE = "playback_state";
    private static final String EXTRA_IS_FOREGROUND = "is_foreground";
    private static final Object LOCK = new Object();

    private static boolean hooksInstalled;
    private static boolean accHookInstalled;
    private static boolean windowManagerHookInstalled;
    private static boolean receiverRegistered;
    private static boolean moduleEnabled;
    private static boolean accState;
    private static boolean snapshotInProgress;
    private static Context systemContext;
    private static Object windowManagerService;
    private static BroadcastReceiver configReceiver;

    @Override
    public boolean supports(String packageName) {
        return "android".equals(packageName);
    }

    @Override
    public void install(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        synchronized (LOCK) {
            if (hooksInstalled) return;
            hooksInstalled = true;
        }

        systemContext = getSystemContext();
        loadConfiguration();
        registerConfigReceiver();
        hookAccReceiver(param.classLoader);
        hookWindowManager(param.classLoader);
    }

    private static void hookAccReceiver(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    ACC_RECEIVER_CLASS,
                    classLoader,
                    "onReceive",
                    Context.class,
                    Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length < 2
                                    || !(param.args[1] instanceof Intent)) return;
                            Context context = param.args[0] instanceof Context
                                    ? (Context) param.args[0] : null;
                            handleAccIntent(context, (Intent) param.args[1]);
                        }
                    });
            synchronized (LOCK) {
                accHookInstalled = true;
            }
            logEvent("accHookInstalled", "MCU ACC receiver hook installed");
        } catch (Throwable error) {
            reportError("accHook", error);
        }
    }

    private static void hookWindowManager(ClassLoader classLoader) {
        try {
            Class<?> type = XposedHelpers.findClass(WINDOW_MANAGER_CLASS, classLoader);
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    synchronized (LOCK) {
                        windowManagerService = param.thisObject;
                    }
                }
            });
            synchronized (LOCK) {
                windowManagerHookInstalled = true;
            }
        } catch (Throwable error) {
            reportError("windowManagerHook", error);
        }
    }

    private static void handleAccIntent(Context context, Intent intent) {
        if (intent == null || !ACC_ACTION.equals(intent.getAction())) return;
        if (context != null) {
            systemContext = context;
            registerConfigReceiver();
        }

        Boolean nextState = readAccStatus(intent);
        if (nextState == null) {
            logEvent("accStatusRejected", "ACC broadcast has no valid AccStatus extra");
            return;
        }

        boolean startSnapshot = false;
        synchronized (LOCK) {
            boolean previousState = accState;
            accState = nextState;

            if (previousState && !accState && moduleEnabled && !snapshotInProgress) {
                snapshotInProgress = true;
                startSnapshot = true;
            }
        }

        logEvent("accStateChanged", "ACC state: " + nextState);
        if (startSnapshot) captureSnapshot();
    }

    private static Boolean readAccStatus(Intent intent) {
        try {
            Object value = intent.getExtras() == null
                    ? null : intent.getExtras().get(ACC_EXTRA);
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) return ((Number) value).intValue() != 0;
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static void loadConfiguration() {
        try {
            XSharedPreferences preferences =
                    new XSharedPreferences(APP_PACKAGE, CONFIG_FILE);
            preferences.reload();
            moduleEnabled = preferences.getBoolean(EXTRA_ENABLED, false);
        } catch (Throwable error) {
            moduleEnabled = false;
            reportError("configurationLoad", error);
        }
    }

    private static void registerConfigReceiver() {
        Context context = systemContext;
        if (context == null) return;

        synchronized (LOCK) {
            if (receiverRegistered) return;
        }

        try {
            IntentFilter filter = new IntentFilter(ACTION_CONFIG_CHANGED);
            configReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null || !ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    Object value = intent.getExtras() == null
                            ? null : intent.getExtras().get(EXTRA_ENABLED);
                    if (!(value instanceof Boolean)) return;

                    synchronized (LOCK) {
                        moduleEnabled = (Boolean) value;
                    }
                    logEvent("configurationChanged", "Media enabled: " + value);
                }
            };
            context.registerReceiver(configReceiver, filter);
            synchronized (LOCK) {
                receiverRegistered = true;
            }
        } catch (RuntimeException error) {
            configReceiver = null;
            reportError("configReceiver", error);
        }
    }

    private static void captureSnapshot() {
        Context context = systemContext;
        String packageName = "";
        String playbackState = "unavailable";
        boolean isForeground = false;

        try {
            Object infoClass = XposedHelpers.findClass(SOURCE_INFO_CLASS, null);
            Object info = XposedHelpers.callStaticMethod(infoClass, "getInstance");
            Object sourceValue = XposedHelpers.callMethod(info, "getSourcePackage");
            packageName = sourceValue instanceof String
                    ? ((String) sourceValue).trim() : "";

            isForeground = isSourceForeground(packageName);

            if (BLUETOOTH_SOURCE_PACKAGE.equals(packageName)) {
                captureBluetoothSnapshot(context, packageName, isForeground);
                return;
            }

            Object musicInfo = XposedHelpers.callMethod(info, "getMusicInfo");
            Object stateValue = musicInfo == null ? null : readField(musicInfo, "state");
            playbackState = stateValue instanceof String
                    ? ((String) stateValue).trim() : "unavailable";
        } catch (Throwable error) {
            reportError("snapshot", error);
        }

        completeSnapshot(context, packageName, playbackState, isForeground);
    }

    private static void captureBluetoothSnapshot(Context context, String packageName,
                                                boolean isForeground) {
        if (context == null) {
            completeSnapshot(null, packageName, "unavailable", isForeground);
            return;
        }

        BluetoothMusicClient client = new BluetoothMusicClient(context, state ->
                completeSnapshot(context, packageName, state, isForeground));

        if (!client.connect()) {
            client.disconnect();
            completeSnapshot(context, packageName, "unavailable", isForeground);
            logEvent("bluetoothBindFailed", "Unable to bind BluetoothMusicService");
        }
    }

    private static void completeSnapshot(Context context, String packageName,
                                         String playbackState, boolean isForeground) {
        synchronized (LOCK) {
            if (!snapshotInProgress) return;
            snapshotInProgress = false;
        }

        String source = packageName == null ? "" : packageName;
        String state = playbackState == null ? "unavailable" : playbackState;

        if (context != null) {
            Intent intent = new Intent(ACTION_STATE_CHANGED);
            intent.setPackage(APP_PACKAGE);
            intent.putExtra(EXTRA_SOURCE_PACKAGE, source);
            intent.putExtra(EXTRA_PLAYBACK_STATE, state);
            intent.putExtra(EXTRA_IS_FOREGROUND, isForeground);
            try {
                context.sendBroadcast(intent);
            } catch (RuntimeException error) {
                reportError("snapshotBroadcast", error);
            }
        }

        logEvent("snapshotReceived", "Source: " + display(source)
                + ", playback: " + display(state)
                + ", foreground: " + isForeground);
    }

    private static boolean isSourceForeground(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;

        Object service;
        synchronized (LOCK) {
            service = windowManagerService;
        }
        if (service == null) return false;

        try {
            Object window = readField(service, "mCurrentFocus");
            Object attributes = window == null ? null : readField(window, "mAttrs");
            Object focusedPackage = attributes == null
                    ? null : readField(attributes, "packageName");
            return packageName.equals(focusedPackage);
        } catch (Throwable error) {
            reportError("foregroundSnapshot", error);
            return false;
        }
    }

    private static Object readField(Object instance, String name) {
        if (instance == null) return null;
        try {
            return XposedHelpers.getObjectField(instance, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context getSystemContext() {
        try {
            Class<?> activityThread = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object thread = XposedHelpers.callStaticMethod(
                    activityThread, "currentActivityThread");
            Object context = XposedHelpers.callMethod(thread, "getSystemContext");
            return context instanceof Context ? (Context) context : null;
        } catch (Throwable error) {
            XposedBridge.log("MT8163 Tools: Media system context unavailable: " + error);
            return null;
        }
    }

    private static void reportError(String source, Throwable error) {
        String message = error == null ? "Unknown error"
                : error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        XposedBridge.log("MT8163 Tools: Media " + source + ": " + message);
        logEvent("hookError", source + ": " + message);
    }

    private static void logEvent(String source, String message) {
        Context context = systemContext;
        if (context == null) {
            XposedBridge.log("MT8163 Tools: Media [" + source + "] " + message);
            return;
        }
        try {
            EventLogger.log(context, "Media", source, message);
        } catch (Throwable error) {
            XposedBridge.log("MT8163 Tools: Media EventLogger failed: " + error);
        }
    }

    private static String display(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }
}
