package com.mt8163.tools.xposed.hooks.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

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
            log("ServiceManager hook failed: " + error);
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
                        publishState(context, (String) value, "");
                    } else if (value != null
                            && "android.sourceservice.MusicInfo".equals(
                            value.getClass().getName())) {
                        Object state = readField(value, "state");
                        publishState(context, null, state instanceof String ? (String) state : "");
                    }
                }
            });
        } catch (Throwable error) {
            log("SourceService callback hooks failed: " + error);
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
            log("Media receiver registration failed: " + error);
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
            log("Media configuration load failed: " + error);
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
                publishState(context,
                        packageValue instanceof String ? (String) packageValue : "",
                        stateValue instanceof String ? (String) stateValue : "");
            } catch (Throwable error) {
                log("SourceInfo snapshot failed: " + error);
                publishState(context, "", "");
            }
        }
    }

    private static void publishState(Context context, String packageName, String state) {
        synchronized (LOCK) {
            if (!moduleEnabled || context == null) return;
            Intent intent = new Intent(ACTION_STATE_CHANGED);
            intent.setPackage(APP_PACKAGE);
            if (packageName != null) intent.putExtra(EXTRA_SOURCE_PACKAGE, packageName);
            if (state != null) intent.putExtra(EXTRA_PLAYBACK_STATE, state);
            try {
                context.sendBroadcast(intent);
            } catch (RuntimeException error) {
                log("Media state broadcast failed: " + error);
            }
        }
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
            log("System context unavailable: " + error);
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
