package com.mt8163.tools.xposed.hooks.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.mt8163.tools.core.EventLogger;
import com.mt8163.tools.xposed.PackageHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MediaHook implements PackageHook {
    private static final String APP_PACKAGE = "com.mt8163.tools";
    private static final String SOURCE_SERVICE_CLASS = "android.sourceservice.SourceService";
    private static final String SOURCE_INFO_CLASS = "android.sourceservice.SourceInfo";
    private static final String BLUETOOTH_SOURCE_PACKAGE = "com.autochips.bluetooth";
    private static final String CONFIG_FILE = "media_hook_config";
    private static final String ACTION_CONFIG_CHANGED =
            "com.mt8163.tools.action.MEDIA_CONFIG_CHANGED";
    private static final String ACTION_REQUEST_SNAPSHOT =
            "com.mt8163.tools.action.MEDIA_REQUEST_SNAPSHOT";
    private static final String ACTION_STATE_CHANGED =
            "com.mt8163.tools.action.MEDIA_STATE_CHANGED";
    private static final String EXTRA_ENABLED = "module_enabled";
    private static final String EXTRA_SOURCE_PACKAGE = "source_package";
    private static final String EXTRA_PLAYBACK_STATE = "playback_state";
    private static final Object LOCK = new Object();

    private static boolean hooksInstalled;
    private static boolean receiverRegistered;
    private static boolean moduleEnabled;
    private static Context systemContext;
    private static Object sourceInfo;
    private static BroadcastReceiver receiver;
    private static String lastSourcePackage;
    private static String lastPlaybackState;
    private static BluetoothMusicClient bluetoothClient;

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

        loadConfiguration();
        hookServiceRegistration();
        hookSourceEvents();

        Object service = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.os.ServiceManager", null),
                "getService", "sourceinfo");
        if (service != null) onSourceServiceAvailable();
    }

    private static void hookServiceRegistration() {
        try {
            Class<?> serviceManager = XposedHelpers.findClass("android.os.ServiceManager", null);
            XposedBridge.hookAllMethods(serviceManager, "addService", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 1
                            && "sourceinfo".equals(param.args[0])) {
                        onSourceServiceAvailable();
                    }
                }
            });
        } catch (Throwable error) {
            reportError(systemContext, "serviceRegistrationHook", error);
        }
    }

    private static void hookSourceEvents() {
        try {
            Class<?> sourceService = XposedHelpers.findClass(SOURCE_SERVICE_CLASS, null);
            XposedBridge.hookAllMethods(sourceService, "callManager", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context context = getServiceContext(param.thisObject);
                    if (context == null || param.args == null || param.args.length == 0) return;

                    Object value = param.args[0];
                    if (value instanceof String) {
                        handleSourceChanged(context, (String) value);
                    } else if (value != null
                            && "android.sourceservice.MusicInfo".equals(
                            value.getClass().getName())) {
                        Object state = readField(value, "state");
                        handlePlaybackState(context, state instanceof String
                                ? (String) state : "");
                    }
                }
            });
        } catch (Throwable error) {
            reportError(systemContext, "sourceEventHook", error);
        }
    }

    private static void onSourceServiceAvailable() {
        Context context = getSystemContext();
        if (context == null) return;

        synchronized (LOCK) {
            systemContext = context;
            if (!receiverRegistered) registerReceiverLocked(context);
        }
        publishSnapshot(context);
    }

    private static void registerReceiverLocked(Context context) {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_CONFIG_CHANGED);
            filter.addAction(ACTION_REQUEST_SNAPSHOT);
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (ACTION_CONFIG_CHANGED.equals(action)) {
                        Object enabled = intent.getExtras() == null
                                ? null : intent.getExtras().get(EXTRA_ENABLED);
                        if (enabled instanceof Boolean) {
                            synchronized (LOCK) {
                                moduleEnabled = (Boolean) enabled;
                            }
                            if (moduleEnabled) publishSnapshot(receiverContext);
                            else disableBluetooth();
                        }
                    } else if (ACTION_REQUEST_SNAPSHOT.equals(action) && moduleEnabled) {
                        publishSnapshot(receiverContext);
                    }
                }
            };
            context.registerReceiver(receiver, filter);
            receiverRegistered = true;
        } catch (RuntimeException error) {
            receiver = null;
            reportError(context, "receiverRegistration", error);
        }
    }

    private static void loadConfiguration() {
        try {
            XSharedPreferences preferences =
                    new XSharedPreferences(APP_PACKAGE, CONFIG_FILE);
            preferences.reload();
            moduleEnabled = preferences.getBoolean(EXTRA_ENABLED, false);
        } catch (Throwable error) {
            moduleEnabled = false;
            reportError(systemContext, "configurationLoad", error);
        }
    }

    private static void publishSnapshot(Context context) {
        synchronized (LOCK) {
            if (!moduleEnabled) return;
            try {
                if (sourceInfo == null) {
                    Class<?> infoClass = XposedHelpers.findClass(SOURCE_INFO_CLASS, null);
                    sourceInfo = XposedHelpers.callStaticMethod(infoClass, "getInstance");
                }
                Object packageValue = XposedHelpers.callMethod(sourceInfo, "getSourcePackage");
                Object musicInfo = XposedHelpers.callMethod(sourceInfo, "getMusicInfo");
                Object stateValue = musicInfo == null ? null : readField(musicInfo, "state");
                String packageName = packageValue instanceof String ? (String) packageValue : "";
                String state = stateValue instanceof String ? (String) stateValue : "";
                logEvent(context, "snapshotReceived",
                        "Source package: " + display(packageName) + ", playback state: "
                                + display(state));
                handleSourceChanged(context, packageName);
                if (!isBluetoothSource(packageName)) handlePlaybackState(context, state);
            } catch (Throwable error) {
                reportError(context, "snapshot", error);
                publishStateLocked(context, "", "", true);
            }
        }
    }

    private static void handleSourceChanged(Context context, String packageName) {
        if (context == null) return;
        String value = packageName == null ? "" : packageName.trim();
        boolean changed;

        synchronized (LOCK) {
            if (!moduleEnabled) return;
            changed = !value.equals(lastSourcePackage);
            lastSourcePackage = value;
            lastPlaybackState = "";
            publishStateLocked(context, value, "", false);
        }

        if (changed) {
            logEvent(context, "sourceChanged", "Audio source package: " + display(value));
        }

        if (isBluetoothSource(value)) enableBluetooth(context);
        else disableBluetooth();
    }

    private static void enableBluetooth(Context context) {
        synchronized (LOCK) {
            if (!moduleEnabled || !isBluetoothSource(lastSourcePackage)
                    || bluetoothClient != null) return;

            BluetoothMusicClient client = new BluetoothMusicClient(context,
                    new BluetoothMusicClient.Listener() {
                        @Override
                        public void onPlaybackState(String state) {
                            publishBluetoothState(context, state);
                        }

                        @Override
                        public void onConnectionLost() {
                            publishBluetoothState(context, "unavailable");
                        }
                    });
            bluetoothClient = client;
            if (!client.connect()) {
                bluetoothClient = null;
                publishBluetoothState(context, "unavailable");
                logEvent(context, "bluetoothBindFailed",
                        "Unable to bind BluetoothMusicService");
            } else {
                logEvent(context, "bluetoothBinding",
                        "Binding BluetoothMusicService for active Bluetooth source");
            }
        }
    }

    private static void disableBluetooth() {
        BluetoothMusicClient client;
        synchronized (LOCK) {
            client = bluetoothClient;
            bluetoothClient = null;
        }
        if (client != null) {
            client.disconnect();
            logEvent(systemContext, "bluetoothUnbound",
                    "Bluetooth source is no longer active");
        }
    }

    private static void publishBluetoothState(Context context, String state) {
        String value = state == null ? "unavailable" : state;
        synchronized (LOCK) {
            if (!moduleEnabled || !isBluetoothSource(lastSourcePackage)) return;
            boolean changed = !value.equals(lastPlaybackState);
            lastPlaybackState = value;
            publishStateLocked(context, null, value, false);
            if (changed) {
                logEvent(context, "bluetoothPlaybackStateChanged",
                        "Bluetooth playback state: " + display(value));
            }
        }
    }

    private static boolean isBluetoothSource(String packageName) {
        return BLUETOOTH_SOURCE_PACKAGE.equals(packageName);
    }

    private static void handlePlaybackState(Context context, String state) {
        if (context == null) return;
        String value = state == null ? "" : state.trim();
        boolean changed;

        synchronized (LOCK) {
            if (!moduleEnabled || isBluetoothSource(lastSourcePackage)) return;
            changed = !value.equals(lastPlaybackState);
            lastPlaybackState = value;
            publishStateLocked(context, null, value, false);
        }

        if (changed) {
            logEvent(context, "playbackStateChanged", "Playback state: " + display(value));
        }
    }

    private static void publishStateLocked(Context context, String packageName,
                                           String state, boolean snapshot) {
        if (!moduleEnabled || context == null) return;
        if (packageName != null) lastSourcePackage = packageName;
        if (state != null) lastPlaybackState = state;

        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.setPackage(APP_PACKAGE);
        if (packageName != null) intent.putExtra(EXTRA_SOURCE_PACKAGE, packageName);
        if (state != null) intent.putExtra(EXTRA_PLAYBACK_STATE, state);
        try {
            context.sendBroadcast(intent);
        } catch (RuntimeException error) {
            reportError(context, "stateBroadcast", error);
        }
    }

    private static void reportError(Context context, String source, Throwable error) {
        String message = error == null ? "Unknown error"
                : error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        XposedBridge.log("MT8163 Tools: Media: " + source + ": " + message);
        if (context != null) logEvent(context, "hookError", source + ": " + message);
    }

    private static void logEvent(Context context, String source, String message) {
        try {
            EventLogger.log(context, "Media", source, message);
        } catch (Throwable error) {
            XposedBridge.log("MT8163 Tools: Media: EventLogger failed: " + error);
        }
    }

    private static String display(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }

    private static Context getServiceContext(Object service) {
        try {
            Object value = XposedHelpers.getObjectField(service, "mContext");
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context getSystemContext() {
        try {
            Class<?> activityThread = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object thread = XposedHelpers.callStaticMethod(activityThread, "currentActivityThread");
            Object context = XposedHelpers.callMethod(thread, "getSystemContext");
            return context instanceof Context ? (Context) context : null;
        } catch (Throwable error) {
            reportError(null, "systemContext", error);
            return null;
        }
    }

    private static Object readField(Object instance, String name) {
        try {
            return XposedHelpers.getObjectField(instance, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void log(String message) {
        XposedBridge.log("MT8163 Tools: Media: " + message);
    }
}
