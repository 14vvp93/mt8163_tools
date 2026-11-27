package com.mt8163.tools.xposed.hooks.media;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.mt8163.tools.core.EventLogger;
import com.mt8163.tools.modules.media.BluetoothMusicClient;
import com.mt8163.tools.modules.media.MediaRestorer;
import com.mt8163.tools.modules.media.MediaSettingsStore;
import com.mt8163.tools.xposed.HookRegistry;
import com.mt8163.tools.xposed.PackageHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.Unhook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.List;
import java.util.Set;

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
    private static final String ACTION_STATE_CHANGED =
            "com.mt8163.tools.action.MEDIA_STATE_CHANGED";
    private static final String ACTION_RESTORE_REQUEST =
            "com.mt8163.tools.action.MEDIA_RESTORE_REQUEST";
    private static final String EXTRA_ENABLED = "module_enabled";
    private static final String EXTRA_RESTORE_BACKGROUND_MEDIA = "restore_background_media";
    private static final String EXTRA_RESTORE_BACKGROUND_BLUETOOTH =
            "restore_background_bluetooth";
    private static final String EXTRA_PLAYBACK_NOTIFICATIONS = "playback_notifications";
    private static final String EXTRA_SOURCE_PACKAGE = "source_package";
    private static final String EXTRA_PLAYBACK_STATE = "playback_state";
    private static final String EXTRA_IS_FOREGROUND = "is_foreground";
    private static final Object LOCK = new Object();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static boolean installInProgress;
    private static boolean installRequested;
    private static boolean accHookInstalled;
    private static boolean windowManagerHookInstalled;
    private static Unhook accHook;
    private static long configurationGeneration;
    private static long lifecycleGeneration;
    private static boolean configurationLoaded;
    private static boolean moduleEnabled;
    private static boolean restoreBackgroundMedia;
    private static boolean restoreBackgroundBluetooth;
    private static boolean playbackNotifications;
    private static boolean accState = true;
    private static boolean snapshotInProgress;
    private static boolean restorePending;
    private static boolean restoreInProgress;
    private static volatile Context systemContext;
    private static Object windowManagerService;
    private static BluetoothMusicClient bluetoothClient;
    private static MediaRestorer.Cancellable activeRestoration;

    @Override
    public boolean supports(String packageName) {
        return "android".equals(packageName);
    }

    @Override
    public String moduleId() {
        return "media";
    }

    @Override
    public void install(XC_LoadPackage.LoadPackageParam param) {
        synchronized (LOCK) {
            if (installInProgress) {
                installRequested = true;
                return;
            }
            if (accHookInstalled && windowManagerHookInstalled) return;
            installInProgress = true;
        }

        boolean completed = false;
        try {
            while (true) {
                boolean loadConfiguration;
                synchronized (LOCK) {
                    installRequested = false;
                    loadConfiguration = !configurationLoaded;
                }

                if (loadConfiguration) loadConfiguration();

                synchronized (LOCK) {
                    if (!windowManagerHookInstalled) {
                        windowManagerHookInstalled = hookWindowManager(param.classLoader);
                    }
                    if (moduleEnabled && !accHookInstalled) {
                        accHook = hookAccReceiver(param.classLoader);
                        accHookInstalled = accHook != null;
                    }

                    if (installRequested) continue;
                    installInProgress = false;
                    completed = true;
                    break;
                }
            }
        } finally {
            if (!completed) {
                synchronized (LOCK) {
                    installInProgress = false;
                    installRequested = false;
                }
            }
        }

        synchronized (LOCK) {
            if (moduleEnabled && !accHookInstalled && !windowManagerHookInstalled) {
                throw new IllegalStateException("No Media hooks were installed");
            }
        }
    }

    private static Unhook hookAccReceiver(ClassLoader classLoader) {
        try {
            Unhook hook = XposedHelpers.findAndHookMethod(
                    ACC_RECEIVER_CLASS,
                    classLoader,
                    "onReceive",
                    Context.class,
                    Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isModuleEnabled()) return;
                            if (param.args == null || param.args.length < 2
                                    || !(param.args[1] instanceof Intent)) return;
                            Intent intent = (Intent) param.args[1];
                            Boolean state = readAccStatus(intent);
                            if (state == null || !state) {
                                handleAccIntent(intent);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isModuleEnabled()) return;
                            if (param.args == null || param.args.length < 2
                                    || !(param.args[1] instanceof Intent)) return;
                            Intent intent = (Intent) param.args[1];
                            if (!Boolean.TRUE.equals(readAccStatus(intent))) return;
                            handleAccIntent(intent);
                        }
                    });
            logEvent("accHookInstalled", "MCU ACC receiver hook installed");
            return hook;
        } catch (Throwable error) {
            reportError("accHook", error);
            return null;
        }
    }

    // This support hook keeps the existing WindowManagerService available across toggles.
    private static boolean hookWindowManager(ClassLoader classLoader) {
        try {
            Class<?> type = XposedHelpers.findClass(WINDOW_MANAGER_CLASS, classLoader);
            Set<Unhook> hooks = XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    synchronized (LOCK) {
                        windowManagerService = param.thisObject;
                    }
                }
            });
            return hooks != null && !hooks.isEmpty();
        } catch (Throwable error) {
            reportError("windowManagerHook", error);
            return false;
        }
    }

    private static boolean isModuleEnabled() {
        synchronized (LOCK) {
            return moduleEnabled;
        }
    }

    private static void handleAccIntent(Intent intent) {
        if (intent == null || !ACC_ACTION.equals(intent.getAction())) return;
        Boolean nextState = readAccStatus(intent);
        if (nextState == null) {
            logEvent("accStatusRejected", "ACC broadcast has no valid AccStatus extra");
            return;
        }

        boolean startSnapshot = false;
        boolean startRestore = false;
        boolean changed;
        MediaRestorer.Cancellable cancelRestore = null;

        synchronized (LOCK) {
            if (!moduleEnabled) return;
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

        if (cancelRestore != null) cancelRestore.cancel();
        if (startSnapshot) captureSnapshot();
        if (startRestore) requestSnapshotForRestore();
        if (changed) {
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

    public static void initializeConfiguration(Context context) {
        if (context == null) return;
        systemContext = context;
        loadConfiguration();
    }

    public static void onConfigurationChanged(Intent intent) {
        if (intent == null
                || !MediaSettingsStore.ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
            return;
        }

        boolean enabled = intent.getBooleanExtra(EXTRA_ENABLED, false);
        boolean changed;
        synchronized (LOCK) {
            changed = moduleEnabled != enabled;
            if (changed) lifecycleGeneration++;
            configurationGeneration++;
            moduleEnabled = enabled;
            restoreBackgroundMedia = intent.getBooleanExtra(
                    EXTRA_RESTORE_BACKGROUND_MEDIA, restoreBackgroundMedia);
            restoreBackgroundBluetooth = intent.getBooleanExtra(
                    EXTRA_RESTORE_BACKGROUND_BLUETOOTH, restoreBackgroundBluetooth);
            playbackNotifications = intent.getBooleanExtra(
                    EXTRA_PLAYBACK_NOTIFICATIONS, playbackNotifications);
            configurationLoaded = true;
        }

        if (changed) {
            HookRegistry.setModuleEnabled("media", enabled);
            logEvent("configurationChanged", "Media enabled: " + enabled);
        }
    }

    private static void loadConfiguration() {
        final long generation;
        synchronized (LOCK) {
            generation = configurationGeneration;
        }

        try {
            XSharedPreferences preferences =
                    new XSharedPreferences(APP_PACKAGE, CONFIG_FILE);
            preferences.reload();

            boolean enabled = preferences.getBoolean(EXTRA_ENABLED, false);
            boolean restoreMedia = preferences.getBoolean(
                    EXTRA_RESTORE_BACKGROUND_MEDIA, false);
            boolean restoreBluetooth = preferences.getBoolean(
                    EXTRA_RESTORE_BACKGROUND_BLUETOOTH, false);
            boolean notifications = preferences.getBoolean(
                    EXTRA_PLAYBACK_NOTIFICATIONS, false);

            synchronized (LOCK) {
                if (configurationGeneration != generation) return;
                moduleEnabled = enabled;
                restoreBackgroundMedia = restoreMedia;
                restoreBackgroundBluetooth = restoreBluetooth;
                playbackNotifications = notifications;
                configurationLoaded = true;
            }
            logEvent("configurationLoaded", "Media settings loaded: enabled=" + enabled);
        } catch (Throwable error) {
            reportError("configurationLoad", error);
        }
    }

    private static void captureSnapshot() {
        final long generation;
        synchronized (LOCK) {
            if (!moduleEnabled || !snapshotInProgress) return;
            generation = lifecycleGeneration;
        }
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
                captureBluetoothSnapshot(context, packageName, isForeground, generation);
                return;
            }

            Object musicInfo = XposedHelpers.callMethod(info, "getMusicInfo");
            Object stateValue = musicInfo == null ? null : readField(musicInfo, "state");
            playbackState = MediaSettingsStore.normalizePlaybackState(
                    stateValue instanceof String ? ((String) stateValue).trim() : null);
        } catch (Throwable error) {
            reportError("snapshot", error);
        }

        completeSnapshot(context, packageName, playbackState, isForeground, generation);
    }

    private static void captureBluetoothSnapshot(Context context, String packageName,
                                                 boolean isForeground, long generation) {
        if (!isModuleEnabled()) return;
        if (context == null) {
            completeSnapshot(null, packageName,
                    MediaSettingsStore.PLAYBACK_UNKNOWN, isForeground, generation);
            return;
        }

        BluetoothMusicClient client = new BluetoothMusicClient(context, state ->
                completeSnapshot(context, packageName,
                        MediaSettingsStore.normalizePlaybackState(state), isForeground, generation));
        boolean accepted;
        synchronized (LOCK) {
            accepted = snapshotInProgress && moduleEnabled
                    && lifecycleGeneration == generation;
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
                    MediaSettingsStore.PLAYBACK_UNKNOWN, isForeground, generation);
            logEvent("bluetoothBindFailed", "Unable to bind BluetoothMusicService");
        }
    }

    private static void completeSnapshot(Context context, String packageName,
                                         String playbackState, boolean isForeground,
                                         long generation) {
        synchronized (LOCK) {
            if (!moduleEnabled || !snapshotInProgress
                    || lifecycleGeneration != generation) return;
            bluetoothClient = null;
        }

        String source = packageName == null ? "" : packageName;
        String state = MediaSettingsStore.normalizePlaybackState(playbackState);

        if (context == null) {
            completeSnapshotSave(false, source, state, isForeground, generation);
            return;
        }

        if (!BLUETOOTH_SOURCE_PACKAGE.equals(source)
                && MediaSettingsStore.PLAYBACK_PLAYING.equals(state)) {
            if (isLifecycleActive(generation)) pauseActivePlayer(source);
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
                    completeSnapshotSave(saved, source, state, isForeground, generation);
                }
            }, null, Activity.RESULT_CANCELED, null, null);
        } catch (RuntimeException error) {
            reportError("snapshotBroadcast", error);
            completeSnapshotSave(false, source, state, isForeground, generation);
        }
    }

    private static void completeSnapshotSave(boolean saved, String source,
                                             String state, boolean isForeground,
                                             long generation) {
        boolean startRestore = false;
        synchronized (LOCK) {
            if (!moduleEnabled || !snapshotInProgress
                    || lifecycleGeneration != generation) return;
            snapshotInProgress = false;
            if (saved) {
                logEvent("snapshotSaved", "sourcePackage=" + display(source)
                        + ", playbackState=" + state
                        + ", isForeground=" + isForeground);
                if (restorePending && accState && moduleEnabled && !restoreInProgress) {
                    restorePending = false;
                    restoreInProgress = true;
                    startRestore = true;
                }
            } else {
                restorePending = false;
                logEvent("snapshotSaveFailed", "sourcePackage=" + display(source)
                        + ", playbackState=" + state
                        + ", isForeground=" + isForeground
                        + ", reason=Snapshot was not persisted");
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
        final long generation;
        synchronized (LOCK) {
            if (!moduleEnabled || !restoreInProgress) return;
            generation = lifecycleGeneration;
        }
        Context context = systemContext;
        if (context == null) {
            finishRestore(generation);
            return;
        }

        Intent request = new Intent(ACTION_RESTORE_REQUEST);
        request.setPackage(APP_PACKAGE);
        try {
            context.sendOrderedBroadcast(request, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context resultContext, Intent resultIntent) {
                    if (!isLifecycleActive(generation)) return;
                    Bundle result = getResultExtras(false);
                    if (getResultCode() != Activity.RESULT_OK || result == null) {
                        logEvent("restoreSnapshot", "No persisted Snapshot available");
                        finishRestore(generation);
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
                        if (lifecycleGeneration != generation || !moduleEnabled
                                || !enabled || !accState || !restoreInProgress) return;
                        restoreBackgroundMedia = restoreMedia;
                        restoreBackgroundBluetooth = restoreBluetooth;
                    }

                    if (packageName.isEmpty()) {
                        logEvent("restoreSnapshot", "Snapshot source is empty");
                        finishRestore(generation);
                        return;
                    }

                    logEvent("restoreStarted", "Source: " + packageName
                            + ", playback: " + playbackState
                            + ", foreground: " + foreground);

                    MediaRestorer.Cancellable operation = MediaRestorer.restore(
                            context, packageName, playbackState, foreground,
                            restoreMedia, restoreBluetooth, result -> {
                                if (isLifecycleActive(generation)) {
                                    showPlaybackToast(context, packageName, result);
                                }
                                finishRestore(generation);
                            });

                    boolean cancelOperation;
                    synchronized (LOCK) {
                        cancelOperation = lifecycleGeneration != generation
                                || !restoreInProgress || !moduleEnabled || !accState;
                        if (!cancelOperation) activeRestoration = operation;
                    }
                    if (cancelOperation) operation.cancel();
                }
            }, null, Activity.RESULT_CANCELED, null, null);
        } catch (RuntimeException error) {
            reportError("restoreSnapshot", error);
            finishRestore(generation);
        }
    }

    private static void finishRestore(long generation) {
        synchronized (LOCK) {
            if (lifecycleGeneration == generation) finishRestoreLocked();
        }
    }

    private static void showPlaybackToast(Context context, String packageName,
                                          MediaRestorer.Result result) {
        if (context == null || result == null) return;

        final String message;
        if (result == MediaRestorer.Result.RESUMED) {
            if (BLUETOOTH_SOURCE_PACKAGE.equals(packageName)) {
                message = "Bluetooth: Playback resumed";
            } else {
                message = getApplicationLabel(context, packageName)
                        + ": Playback resumed";
            }
        } else if (result == MediaRestorer.Result.FAILED) {
            message = "Failed to resume playback";
        } else {
            return;
        }

        MAIN_HANDLER.post(() -> {
            synchronized (LOCK) {
                if (!moduleEnabled || !accState || !playbackNotifications) return;
            }
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            } catch (RuntimeException error) {
                reportError("playbackToast", error);
            }
        });
    }

    private static String getApplicationLabel(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return "Media Player";
        try {
            PackageManager packageManager = context.getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(
                    packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(applicationInfo);
            String value = label == null ? "" : label.toString().trim();
            return value.isEmpty() ? "Media Player" : value;
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return "Media Player";
        }
    }

    private static boolean isLifecycleActive(long generation) {
        synchronized (LOCK) {
            return moduleEnabled && lifecycleGeneration == generation;
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

    private static void reportError(String source, Throwable error) {
        String message = error == null ? "Unknown error"
                : error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        XposedBridge.log("MT8163 Tools: Media " + source + ": " + message);
        logEvent("hookError", source + ": " + message);
    }

    @Override
    public void uninstall() {
        Unhook receiverHook;
        BluetoothMusicClient client;
        MediaRestorer.Cancellable restoration;

        synchronized (LOCK) {
            moduleEnabled = false;
            lifecycleGeneration++;
            snapshotInProgress = false;
            restorePending = false;
            restoreInProgress = false;

            receiverHook = accHook;
            accHook = null;
            accHookInstalled = false;

            client = bluetoothClient;
            bluetoothClient = null;
            restoration = activeRestoration;
            activeRestoration = null;
        }

        if (receiverHook != null) {
            try {
                receiverHook.unhook();
            } catch (Throwable error) {
                reportError("accUnhook", error);
            }
        }
        if (client != null) client.disconnect();
        if (restoration != null) restoration.cancel();

        logEvent("moduleDisabled", "Media operations stopped and ACC hook removed");
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
