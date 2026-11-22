package com.mt8163.tools.xposed.hooks.media;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Bundle;

import com.mt8163.tools.core.EventLogger;
import com.mt8163.tools.modules.media.BluetoothMusicClient;
import com.mt8163.tools.modules.media.MediaRestorer;
import com.mt8163.tools.modules.media.MediaSettingsStore;
import com.mt8163.tools.xposed.PackageHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.List;

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
    private static final String CONFIG_PERMISSION =
            "com.mt8163.tools.permission.UPDATE_MEDIA_CONFIG";
    private static final String ACTION_STATE_CHANGED =
            "com.mt8163.tools.action.MEDIA_STATE_CHANGED";
    private static final String ACTION_RESTORE_REQUEST =
            "com.mt8163.tools.action.MEDIA_RESTORE_REQUEST";
    private static final String EXTRA_ENABLED = "module_enabled";
    private static final String EXTRA_RESTORE_BACKGROUND_MEDIA = "restore_background_media";
    private static final String EXTRA_RESTORE_BACKGROUND_BLUETOOTH =
            "restore_background_bluetooth";
    private static final String EXTRA_SOURCE_PACKAGE = "source_package";
    private static final String EXTRA_PLAYBACK_STATE = "playback_state";
    private static final String EXTRA_IS_FOREGROUND = "is_foreground";
    private static final Object LOCK = new Object();

    private static boolean hooksInstalled;
    private static boolean receiverRegistered;
    private static boolean moduleEnabled;
    private static boolean restoreBackgroundMedia;
    private static boolean restoreBackgroundBluetooth;
    private static boolean accState;
    private static boolean accStateInitialized;
    private static boolean snapshotInProgress;
    private static boolean restorePending;
    private static boolean restoreInProgress;
    private static Context systemContext;
    private static Object windowManagerService;
    private static BluetoothMusicClient bluetoothClient;
    private static MediaRestorer.Cancellable activeRestoration;

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
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length < 2
                                    || !(param.args[1] instanceof Intent)) return;
                            Intent intent = (Intent) param.args[1];
                            Boolean state = readAccStatus(intent);
                            if (state == null || !state) {
                                Context context = param.args[0] instanceof Context
                                        ? (Context) param.args[0] : null;
                                handleAccIntent(context, intent);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length < 2
                                    || !(param.args[1] instanceof Intent)) return;
                            Intent intent = (Intent) param.args[1];
                            if (!Boolean.TRUE.equals(readAccStatus(intent))) return;
                            Context context = param.args[0] instanceof Context
                                    ? (Context) param.args[0] : null;
                            handleAccIntent(context, intent);
                        }
                    });
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
        boolean startRestore = false;
        boolean changed;
        boolean initializedNow = false;
        MediaRestorer.Cancellable cancelRestore = null;

        synchronized (LOCK) {
            if (!accStateInitialized) {
                accState = nextState;
                accStateInitialized = true;
                changed = true;
                initializedNow = true;

                if (accState && moduleEnabled) {
                    if (snapshotInProgress) {
                        restorePending = true;
                    } else if (!restoreInProgress) {
                        restoreInProgress = true;
                        startRestore = true;
                    }
                }
            } else {
                boolean previousState = accState;
                accState = nextState;
                changed = previousState != accState;

                if (previousState && !accState) {
                    restorePending = false;
                    cancelRestore = activeRestoration;
                    activeRestoration = null;
                    restoreInProgress = false;

                    if (moduleEnabled && !snapshotInProgress) {
                        snapshotInProgress = true;
                        startSnapshot = true;
                    }
                } else if (!previousState && accState && moduleEnabled) {
                    if (snapshotInProgress) {
                        restorePending = true;
                    } else if (!restoreInProgress) {
                        restoreInProgress = true;
                        startRestore = true;
                    }
                }
            }
        }

        if (cancelRestore != null) cancelRestore.cancel();
        if (startSnapshot) captureSnapshot();
        if (startRestore) requestSnapshotForRestore();
        if (initializedNow) {
            logEvent("accStateInitialized", "Initial ACC state: " + nextState);
        } else if (changed) {
            logEvent("accStateChanged", "ACC state: " + nextState);
        }
    }

    private static Boolean readAccStatus(Intent intent) {
        try {
            Object value = intent.getExtras() == null
                    ? null : intent.getExtras().get(ACC_EXTRA);
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) return ((Number) value).doubleValue() != 0.0d;
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
            restoreBackgroundMedia = preferences.getBoolean(
                    EXTRA_RESTORE_BACKGROUND_MEDIA, restoreBackgroundMedia);
            restoreBackgroundBluetooth = preferences.getBoolean(
                    EXTRA_RESTORE_BACKGROUND_BLUETOOTH, restoreBackgroundBluetooth);
        } catch (Throwable error) {
            moduleEnabled = false;
            restoreBackgroundMedia = false;
            restoreBackgroundBluetooth = false;
            reportError("configurationLoad", error);
        }
    }

    private static void registerConfigReceiver() {
        Context context = systemContext;
        if (context == null) return;

        synchronized (LOCK) {
            if (receiverRegistered) return;
            receiverRegistered = true;
        }

        try {
            IntentFilter filter = new IntentFilter(ACTION_CONFIG_CHANGED);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null || !ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
                        return;
                    }

                    BluetoothMusicClient cancelClient = null;
                    MediaRestorer.Cancellable cancelRestore = null;
                    boolean enabled = intent.getBooleanExtra(EXTRA_ENABLED, false);
                    synchronized (LOCK) {
                        moduleEnabled = enabled;
                        restoreBackgroundMedia = intent.getBooleanExtra(
                                EXTRA_RESTORE_BACKGROUND_MEDIA, restoreBackgroundMedia);
                        restoreBackgroundBluetooth = intent.getBooleanExtra(
                                EXTRA_RESTORE_BACKGROUND_BLUETOOTH,
                                restoreBackgroundBluetooth);

                        if (!enabled) {
                            snapshotInProgress = false;
                            restorePending = false;
                            restoreInProgress = false;
                            cancelClient = bluetoothClient;
                            bluetoothClient = null;
                            cancelRestore = activeRestoration;
                            activeRestoration = null;
                        }
                    }

                    if (cancelClient != null) cancelClient.disconnect();
                    if (cancelRestore != null) cancelRestore.cancel();
                    logEvent("configurationChanged", "Media enabled: " + enabled);
                }
            };
            context.registerReceiver(receiver, filter, CONFIG_PERMISSION, null);
        } catch (RuntimeException error) {
            synchronized (LOCK) {
                receiverRegistered = false;
            }
            reportError("configReceiver", error);
        }
    }

    private static void captureSnapshot() {
        Context context = systemContext;
        String packageName = "";
        String playbackState = MediaSettingsStore.PLAYBACK_UNKNOWN;
        boolean isForeground = false;

        try {
            Class<?> infoClass = XposedHelpers.findClass(SOURCE_INFO_CLASS, null);
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
            playbackState = MediaSettingsStore.normalizePlaybackState(
                    stateValue instanceof String ? ((String) stateValue).trim() : null);
        } catch (Throwable error) {
            reportError("snapshot", error);
        }

        completeSnapshot(context, packageName, playbackState, isForeground);
    }

    private static void captureBluetoothSnapshot(Context context, String packageName,
                                                 boolean isForeground) {
        if (context == null) {
            completeSnapshot(null, packageName,
                    MediaSettingsStore.PLAYBACK_UNKNOWN, isForeground);
            return;
        }

        BluetoothMusicClient client = new BluetoothMusicClient(context, state ->
                completeSnapshot(context, packageName,
                        MediaSettingsStore.normalizePlaybackState(state), isForeground));
        boolean accepted;
        synchronized (LOCK) {
            accepted = snapshotInProgress && moduleEnabled;
            if (accepted) bluetoothClient = client;
        }
        if (!accepted) {
            client.disconnect();
            return;
        }

        if (!client.connect()) {
            synchronized (LOCK) {
                if (bluetoothClient == client) bluetoothClient = null;
            }
            client.disconnect();
            completeSnapshot(context, packageName,
                    MediaSettingsStore.PLAYBACK_UNKNOWN, isForeground);
            logEvent("bluetoothBindFailed", "Unable to bind BluetoothMusicService");
        }
    }

    private static void completeSnapshot(Context context, String packageName,
                                         String playbackState, boolean isForeground) {
        synchronized (LOCK) {
            if (!moduleEnabled || !snapshotInProgress) return;
            bluetoothClient = null;
        }

        String source = packageName == null ? "" : packageName;
        String state = MediaSettingsStore.normalizePlaybackState(playbackState);

        if (context == null) {
            completeSnapshotSave(false, source, state, isForeground);
            return;
        }

        if (!BLUETOOTH_SOURCE_PACKAGE.equals(source)
                && MediaSettingsStore.PLAYBACK_PLAYING.equals(state)) {
            pauseActivePlayer(source);
        }

        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.setPackage(APP_PACKAGE);
        intent.putExtra(EXTRA_SOURCE_PACKAGE, source);
        intent.putExtra(EXTRA_PLAYBACK_STATE, state);
        intent.putExtra(EXTRA_IS_FOREGROUND, isForeground);
        try {
            context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context resultContext, Intent resultIntent) {
                    boolean saved = getResultCode() == Activity.RESULT_OK;
                    completeSnapshotSave(saved, source, state, isForeground);
                }
            }, null, Activity.RESULT_CANCELED, null, null);
        } catch (RuntimeException error) {
            reportError("snapshotBroadcast", error);
            completeSnapshotSave(false, source, state, isForeground);
        }
    }

    private static void completeSnapshotSave(boolean saved, String source,
                                             String state, boolean isForeground) {
        boolean startRestore = false;
        synchronized (LOCK) {
            snapshotInProgress = false;
            if (saved) {
                logEvent("snapshotReceived", "Source: " + display(source)
                        + ", playback: " + state
                        + ", foreground: " + isForeground);
                if (restorePending && accState && moduleEnabled && !restoreInProgress) {
                    restorePending = false;
                    restoreInProgress = true;
                    startRestore = true;
                }
            } else {
                restorePending = false;
                logEvent("snapshotSaveFailed", "Snapshot was not persisted");
            }
        }
        if (startRestore) requestSnapshotForRestore();
    }

    private static void pauseActivePlayer(String packageName) {
        Context context = systemContext;
        if (context == null || packageName == null || packageName.isEmpty()) return;

        try {
            MediaSessionManager manager = (MediaSessionManager) context.getSystemService(
                    Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return;

            List<MediaController> sessions = manager.getActiveSessions(null);
            if (sessions == null) return;
            for (MediaController controller : sessions) {
                if (controller == null || !packageName.equals(controller.getPackageName())) {
                    continue;
                }
                controller.getTransportControls().pause();
                logEvent("playerPause", "MediaSession pause sent to " + packageName);
                return;
            }
            logEvent("playerPause", "No active MediaSession for " + packageName);
        } catch (Throwable error) {
            reportError("playerPause", error);
        }
    }

    private static void requestSnapshotForRestore() {
        Context context = systemContext;
        if (context == null) {
            finishRestore();
            return;
        }

        Intent request = new Intent(ACTION_RESTORE_REQUEST);
        request.setPackage(APP_PACKAGE);
        try {
            context.sendOrderedBroadcast(request, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context resultContext, Intent resultIntent) {
                    Bundle result = getResultExtras(false);
                    if (getResultCode() != Activity.RESULT_OK || result == null) {
                        logEvent("restoreSnapshot", "No persisted Snapshot available");
                        finishRestore();
                        return;
                    }

                    String packageName = result.getString(EXTRA_SOURCE_PACKAGE, "");
                    String playbackState = MediaSettingsStore.normalizePlaybackState(
                            result.getString(EXTRA_PLAYBACK_STATE));
                    boolean foreground = result.getBoolean(EXTRA_IS_FOREGROUND, false);
                    boolean enabled = result.getBoolean(EXTRA_ENABLED, moduleEnabled);
                    boolean restoreMedia = result.getBoolean(
                            EXTRA_RESTORE_BACKGROUND_MEDIA, restoreBackgroundMedia);
                    boolean restoreBluetooth = result.getBoolean(
                            EXTRA_RESTORE_BACKGROUND_BLUETOOTH, restoreBackgroundBluetooth);

                    synchronized (LOCK) {
                        if (!moduleEnabled || !enabled || !accState || !restoreInProgress) {
                            finishRestoreLocked();
                            return;
                        }
                        restoreBackgroundMedia = restoreMedia;
                        restoreBackgroundBluetooth = restoreBluetooth;
                    }

                    if (packageName.isEmpty()) {
                        logEvent("restoreSnapshot", "Snapshot source is empty");
                        finishRestore();
                        return;
                    }

                    logEvent("restoreStarted", "Source: " + packageName
                            + ", playback: " + playbackState
                            + ", foreground: " + foreground);

                    MediaRestorer.Cancellable operation = MediaRestorer.restore(
                            context, packageName, playbackState, foreground,
                            restoreMedia, restoreBluetooth, MediaHook::finishRestore);

                    boolean cancelOperation;
                    synchronized (LOCK) {
                        cancelOperation = !restoreInProgress || !moduleEnabled || !accState;
                        if (!cancelOperation) activeRestoration = operation;
                    }
                    if (cancelOperation) operation.cancel();
                }
            }, null, Activity.RESULT_CANCELED, null, null);
        } catch (RuntimeException error) {
            reportError("restoreSnapshot", error);
            finishRestore();
        }
    }

    private static void finishRestore() {
        synchronized (LOCK) {
            finishRestoreLocked();
        }
    }

    private static void finishRestoreLocked() {
        restoreInProgress = false;
        activeRestoration = null;
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
