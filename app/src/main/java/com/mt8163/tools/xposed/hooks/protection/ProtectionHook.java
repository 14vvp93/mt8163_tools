package com.mt8163.tools.xposed.hooks.protection;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.view.ViewParent;

import com.mt8163.tools.modules.protection.ProtectionConfig;
import com.mt8163.tools.modules.protection.ProtectionSettingsStore;
import com.mt8163.tools.modules.protection.ProtectionTaskMatcher;
import com.mt8163.tools.xposed.PackageHook;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class ProtectionHook implements PackageHook {
    private static final String TARGET_PACKAGE = "com.android.systemui";
    private static final String APPLICATION_CLASS =
            "com.android.systemui.SystemUIApplication";
    private static final String APP_PACKAGE = "com.mt8163.tools";
    private static final String SETTINGS_FILE = "protection_hook_config";
    private static final String KEY_ENABLED = "module_enabled";
    private static final String KEY_APPLICATIONS = "applications";
    private static final Object LOCK = new Object();
    private static final ThreadLocal<CleanerState> CLEANER_STATE = new ThreadLocal<>();
    private static final List<XC_MethodHook.Unhook> FEATURE_HOOKS = new ArrayList<>();
    private static final List<XC_MethodHook.Unhook> SUPPORT_HOOKS = new ArrayList<>();

    private static boolean configurationHookInstalled;
    private static boolean recentsLifecycleHooksInstalled;
    private static boolean featureHookInstallFailed;
    private static boolean hooksInstalled;
    private static boolean receiverRegistered;
    private static volatile boolean recentsStackModified;
    private static ClassLoader systemUiClassLoader;
    private static WeakReference<Activity> recentsActivity = new WeakReference<>(null);
    private static BroadcastReceiver configurationReceiver;

    @Override
    public boolean supports(String packageName) {
        return TARGET_PACKAGE.equals(packageName);
    }

    @Override
    public void install(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        if (param == null || param.classLoader == null
                || !TARGET_PACKAGE.equals(param.packageName)
                || !TARGET_PACKAGE.equals(param.processName)) {
            return;
        }

        synchronized (LOCK) {
            systemUiClassLoader = param.classLoader;
            if (!supportHooksInstalled) {
                installSupportHooks(param.classLoader);
            }
        }

        loadConfiguration();
        XposedBridge.log("MT8163 Tools: Protection SystemUI support hooks installed");
    }

    @Override
    public String moduleId() {
        return "protection";
    }

    @Override
    public void uninstall() {
        ProtectionTaskMatcher.update(ProtectionConfig.empty());
        disableFeatureHooks();
    }

    private static void installSupportHooks(ClassLoader loader) throws Throwable {
        if (!configurationHookInstalled) {
            XC_MethodHook.Unhook configurationHook = XposedHelpers.findAndHookMethod(
                    APPLICATION_CLASS,
                    loader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hookParam) {
                            if (hookParam.thisObject instanceof Context) {
                                registerConfigurationReceiver((Context) hookParam.thisObject);
                            }
                        }
                    });
            SUPPORT_HOOKS.add(configurationHook);
            configurationHookInstalled = true;
            XposedBridge.log("MT8163 Tools: Protection configuration hook installed");
        }

        if (!recentsLifecycleHooksInstalled) {
            try {
                SUPPORT_HOOKS.addAll(installRecentsLifecycleHooks(loader));
                recentsLifecycleHooksInstalled = true;
                XposedBridge.log("MT8163 Tools: Protection Recents lifecycle hooks installed");
            } catch (Throwable error) {
                XposedBridge.log("MT8163 Tools: Protection Recents lifecycle hooks failed: "
                        + error);
            }
        }
    }

    private static List<XC_MethodHook.Unhook> installRecentsLifecycleHooks(
            ClassLoader loader) throws Throwable {
        List<XC_MethodHook.Unhook> installed = new ArrayList<>();
        try {
            installed.add(XposedHelpers.findAndHookMethod(
                    "com.android.systemui.recents.RecentsActivity",
                    loader,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) {
                                synchronized (LOCK) {
                                    recentsActivity = new WeakReference<>(
                                            (Activity) param.thisObject);
                                }
                            }
                        }
                    }));

            installed.add(XposedHelpers.findAndHookMethod(
                    "com.android.systemui.recents.RecentsActivity",
                    loader,
                    "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            synchronized (LOCK) {
                                if (recentsActivity.get() == param.thisObject) {
                                    recentsActivity = new WeakReference<>(null);
                                }
                            }
                        }
                    }));
            return installed;
        } catch (Throwable error) {
            unhookAll(installed, "support");
            throw error;
        }
    }

    private static void enableFeatureHooks() {
        synchronized (LOCK) {
            if (hooksInstalled || systemUiClassLoader == null) return;

            featureHookInstallFailed = false;
            installRecentsHooks(systemUiClassLoader);
            installMemoryCleanerHooks(systemUiClassLoader);

            if (featureHookInstallFailed) {
                unhookAll(FEATURE_HOOKS, "feature");
                FEATURE_HOOKS.clear();
                hooksInstalled = false;
                XposedBridge.log("MT8163 Tools: Protection feature installation rolled back");
                return;
            }

            hooksInstalled = true;
        }
    }

    private static void disableFeatureHooks() {
        synchronized (LOCK) {
            unhookAll(FEATURE_HOOKS, "feature");
            FEATURE_HOOKS.clear();
            hooksInstalled = false;
            CLEANER_STATE.remove();
        }
        refreshRecentsIfNeeded();
    }

    private static void refreshRecentsIfNeeded() {
        if (!recentsStackModified) return;

        Activity activity;
        synchronized (LOCK) {
            activity = recentsActivity.get();
        }

        if (activity == null || !Boolean.TRUE.equals(readField(activity, "mIsVisible"))) {
            recentsStackModified = false;
            return;
        }

        WeakReference<Activity> activityRef = new WeakReference<>(activity);
        activity.runOnUiThread(() -> {
            Activity currentActivity = activityRef.get();
            if (currentActivity == null) return;

            synchronized (LOCK) {
                if (recentsActivity.get() != currentActivity) return;
            }

            if (currentActivity.isFinishing() || currentActivity.isDestroyed()
                    || !Boolean.TRUE.equals(readField(currentActivity, "mIsVisible"))) {
                recentsStackModified = false;
                return;
            }

            try {
                XposedHelpers.callMethod(currentActivity, "reloadTaskStack",
                        currentActivity.isInMultiWindowMode(), false);
                recentsStackModified = false;
                XposedBridge.log("MT8163 Tools: Protection Recents stack refreshed");
            } catch (Throwable error) {
                XposedBridge.log("MT8163 Tools: Protection Recents refresh failed: " + error);
            }
        });
    }

    private static void installRecentsHooks(ClassLoader loader) {
        installHook("Recents swipe dismissal", () ->
                XposedHelpers.findAndHookMethod(
                        "com.android.systemui.recents.views.TaskStackViewTouchHandler",
                        loader,
                        "canChildBeDismissed",
                        View.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args == null || param.args.length == 0
                                        || !(param.args[0] instanceof View)) return;
                                Object task = getTaskFromView((View) param.args[0]);
                                if (isProtected(task, ProtectionType.KEEP)) {
                                    param.setResult(false);
                                }
                            }
                        }));

        installHook("Recents card X dismissal", () ->
                XposedHelpers.findAndHookMethod(
                        "com.android.systemui.recents.views.TaskViewHeader",
                        loader,
                        "onClick",
                        View.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args == null || param.args.length == 0
                                        || !(param.args[0] instanceof View)) return;
                                Object dismissButton = readField(param.thisObject,
                                        "mDismissButton");
                                if (dismissButton != param.args[0]) return;

                                Object taskView = findParentTaskView((View) param.thisObject);
                                Object task = taskView == null ? null
                                        : call(taskView, "getTask");
                                TaskIdentity identity = resolveTask(task);
                                if (identity != null
                                        && ProtectionTaskMatcher.shouldKeep(identity.packageName)
                                        && !ProtectionTaskMatcher.shouldAllowCloseViaX(
                                        identity.packageName)) {
                                    param.setResult(null);
                                }
                            }
                        }));

        installHook("Recents keyboard dismissal", () ->
                XposedHelpers.findAndHookMethod(
                        "com.android.systemui.recents.views.TaskStackView",
                        loader,
                        "onBusEvent",
                        XposedHelpers.findClass(
                                "com.android.systemui.recents.events.ui.focus."
                                        + "DismissFocusedTaskViewEvent", loader),
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Object task = readField(param.thisObject, "mFocusedTask");
                                if (!isProtected(task, ProtectionType.KEEP)) return;

                                call(param.thisObject, "resetFocusedTask", task);
                                param.setResult(null);
                            }
                        }));

        installHook("Recents Close all", () ->
                XposedHelpers.findAndHookMethod(
                        "com.android.systemui.recents.views.TaskStackView",
                        loader,
                        "onBusEvent",
                        XposedHelpers.findClass(
                                "com.android.systemui.recents.events.ui."
                                        + "DismissAllTaskViewsEvent", loader),
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                handleDismissAll(param.thisObject, loader, param);
                            }
                        }));

        installHook("Recents initial stack filtering", () ->
                XposedHelpers.findAndHookMethod(
                        "com.android.systemui.recents.views.RecentsView",
                        loader,
                        "onReload",
                        XposedHelpers.findClass(
                                "com.android.systemui.shared.recents.model.TaskStack", loader),
                        boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args != null && param.args.length > 0) {
                                    filterVisibleStack(param.args[0]);
                                }
                            }
                        }));

        installHook("Recents deferred stack filtering", () ->
                XposedHelpers.findAndHookConstructor(
                        "com.android.systemui.recents.events.activity."
                                + "MultiWindowStateChangedEvent",
                        loader,
                        boolean.class,
                        boolean.class,
                        XposedHelpers.findClass(
                                "com.android.systemui.shared.recents.model.TaskStack", loader),
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (param.args != null && param.args.length > 2) {
                                    filterVisibleStack(param.args[2]);
                                }
                            }
                        }));
    }

    private static void installMemoryCleanerHooks(ClassLoader loader) {
        installHook("Memory Cleaner lifecycle", () ->
                XposedHelpers.findAndHookMethod(
                        "com.android.systemui.qs.tiles.MemoryCleanerTile",
                        loader,
                        "clearRecentsTask",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                CLEANER_STATE.set(new CleanerState());
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                CLEANER_STATE.remove();
                            }
                        }));

        installHook("Memory Cleaner task data", () ->
                XposedHelpers.findAndHookMethod(
                        "com.android.systemui.shared.recents.model.RecentsTaskLoader",
                        loader,
                        "deleteTaskData",
                        XposedHelpers.findClass(
                                "com.android.systemui.shared.recents.model.Task", loader),
                        boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                CleanerState state = CLEANER_STATE.get();
                                if (state == null || param.args == null
                                        || param.args.length == 0) return;

                                TaskIdentity identity = resolveTask(param.args[0]);
                                state.protectCurrentTask = identity != null
                                        && ProtectionTaskMatcher.shouldProtectCleaner(
                                        identity.packageName);
                                state.taskId = identity == null ? -1 : identity.taskId;
                                state.packageName = identity == null
                                        ? null : identity.packageName;
                                if (state.protectCurrentTask) param.setResult(null);
                            }
                        }));

        installHook("Memory Cleaner removeTask", () ->
                XposedHelpers.findAndHookMethod(
                        "com.android.systemui.shared.system.ActivityManagerWrapper",
                        loader,
                        "removeTask",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                CleanerState state = CLEANER_STATE.get();
                                if (state != null && state.protectCurrentTask
                                        && param.args != null && param.args.length > 0
                                        && param.args[0] instanceof Number
                                        && ((Number) param.args[0]).intValue() == state.taskId) {
                                    param.setResult(null);
                                }
                            }
                        }));

        installHook("Memory Cleaner forceStopPackage", () ->
                XposedHelpers.findAndHookMethod(
                        ActivityManager.class,
                        "forceStopPackage",
                        String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                CleanerState state = CLEANER_STATE.get();
                                if (state != null && state.protectCurrentTask
                                        && param.args != null && param.args.length > 0
                                        && state.packageName != null
                                        && state.packageName.equals(param.args[0])) {
                                    param.setResult(null);
                                }
                            }
                        }));
    }

    private static void handleDismissAll(Object stackView, ClassLoader loader,
                                         XC_MethodHook.MethodHookParam param) {
        Object stack = call(stackView, "getStack");
        if (stack == null) return;

        Object taskList = call(stack, "getTasks");
        if (!(taskList instanceof List)) return;

        List<?> tasks = new ArrayList<>((List<?>) taskList);
        boolean hasProtectedTask = false;
        for (Object task : tasks) {
            if (isProtected(task, ProtectionType.KEEP)) {
                hasProtectedTask = true;
                break;
            }
        }
        if (!hasProtectedTask) return;

        param.setResult(null);

        Object focusedTask = call(stackView, "getFocusedTask");
        if (focusedTask != null && !isProtected(focusedTask, ProtectionType.KEEP)) {
            call(stackView, "resetFocusedTask", focusedTask);
        }

        for (Object task : tasks) {
            if (isProtected(task, ProtectionType.KEEP)) continue;

            Object taskView = call(stackView, "getChildViewForTask", task);
            if (taskView != null) {
                call(taskView, "dismissTask");
            } else {
                removeTaskData(stack, task, loader);
            }
        }

        closeRecentsWindow(loader);
    }

    private static void closeRecentsWindow(ClassLoader loader) {
        try {
            Class<?> eventClass = XposedHelpers.findClass(
                    "com.android.systemui.recents.events.activity.HideRecentsEvent",
                    loader);
            Object event = XposedHelpers.newInstance(eventClass, false, true);
            Class<?> busClass = XposedHelpers.findClass(
                    "com.android.systemui.recents.events.EventBus", loader);
            Object bus = XposedHelpers.callStaticMethod(busClass, "getDefault");
            XposedHelpers.callMethod(bus, "send", event);
        } catch (Throwable error) {
            XposedBridge.log("MT8163 Tools: Protection Recents close failed: " + error);
        }
    }

    private static void removeTaskData(Object stack, Object task, ClassLoader loader) {
        try {
            XposedHelpers.callMethod(stack, "removeTask", task, null, false, false);
            Class<?> eventClass = XposedHelpers.findClass(
                    "com.android.systemui.recents.events.ui.DeleteTaskDataEvent", loader);
            Object event = XposedHelpers.newInstance(eventClass, task);
            Class<?> busClass = XposedHelpers.findClass(
                    "com.android.systemui.recents.events.EventBus", loader);
            Object bus = XposedHelpers.callStaticMethod(busClass, "getDefault");
            XposedHelpers.callMethod(bus, "send", event);
        } catch (Throwable error) {
            XposedBridge.log("MT8163 Tools: Protection Close all cleanup failed: " + error);
        }
    }

    private static void filterVisibleStack(Object stack) {
        if (stack == null) return;

        Object value = call(stack, "getTasks");
        if (!(value instanceof List)) return;

        List<?> tasks = (List<?>) value;
        ArrayList<Object> visibleTasks = new ArrayList<>(tasks.size());
        boolean changed = false;

        for (Object task : tasks) {
            if (isProtected(task, ProtectionType.HIDE)) {
                changed = true;
            } else {
                visibleTasks.add(task);
            }
        }

        if (changed) {
            call(stack, "setTasks", visibleTasks, false);
            recentsStackModified = true;
        }
    }

    private static Object getTaskFromView(View view) {
        return call(view, "getTask");
    }

    private static Object findParentTaskView(View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if ("com.android.systemui.recents.views.TaskView"
                    .equals(parent.getClass().getName())) {
                return parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private static boolean isProtected(Object task, ProtectionType type) {
        TaskIdentity identity = resolveTask(task);
        if (identity == null) return false;

        switch (type) {
            case KEEP:
                return ProtectionTaskMatcher.shouldKeep(
                        identity.packageName);
            case HIDE:
                return ProtectionTaskMatcher.shouldHide(
                        identity.packageName);
            case CLEANER:
                return ProtectionTaskMatcher.shouldProtectCleaner(
                        identity.packageName);
            default:
                return false;
        }
    }

    private static TaskIdentity resolveTask(Object task) {
        if (task == null) return null;

        Object key = readField(task, "key");
        if (key == null) return null;

        Object intent = readField(key, "baseIntent");
        if (intent == null) return null;

        String packageName = null;
        Object component = call(intent, "getComponent");
        if (component != null) {
            Object value = call(component, "getPackageName");
            if (value instanceof String) packageName = (String) value;
        }
        if (packageName == null || packageName.isEmpty()) {
            Object value = call(intent, "getPackage");
            if (value instanceof String) packageName = (String) value;
        }
        if (!ProtectionConfig.isValidPackageName(packageName)) return null;

        try {
            int taskId = XposedHelpers.getIntField(key, "id");
            return new TaskIdentity(packageName, taskId);
        } catch (Throwable ignored) {
            return null;
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

    private static Object call(Object instance, String method, Object... args) {
        if (instance == null) return null;
        try {
            return XposedHelpers.callMethod(instance, method, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void registerConfigurationReceiver(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) return;

        synchronized (LOCK) {
            if (receiverRegistered) return;

            configurationReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null
                            || !ProtectionSettingsStore.ACTION_CONFIG_CHANGED.equals(
                            intent.getAction())) return;
                    loadConfiguration();
                }
            };

            try {
                appContext.registerReceiver(configurationReceiver,
                        new IntentFilter(ProtectionSettingsStore.ACTION_CONFIG_CHANGED),
                        "com.mt8163.tools.permission.UPDATE_PROTECTION_CONFIG",
                        null);
                receiverRegistered = true;
            } catch (Throwable error) {
                XposedBridge.log("MT8163 Tools: Protection receiver registration failed: "
                        + error);
                return;
            }
        }

        loadConfiguration();
        XposedBridge.log("MT8163 Tools: Protection configuration receiver registered");
    }

    private static void loadConfiguration() {
        ProtectionConfig config;
        try {
            XSharedPreferences preferences =
                    new XSharedPreferences(APP_PACKAGE, SETTINGS_FILE);
            preferences.reload();

            boolean enabled = preferences.getBoolean(KEY_ENABLED, false);
            String applications = preferences.getString(KEY_APPLICATIONS, "{}");
            config = ProtectionConfig.fromJson(enabled, applications);
        } catch (Throwable error) {
            XposedBridge.log("MT8163 Tools: Protection configuration load failed: " + error);
            config = ProtectionConfig.empty();
        }

        ProtectionTaskMatcher.update(config);
        if (config.isEnabled()) {
            enableFeatureHooks();
        } else {
            disableFeatureHooks();
        }
    }

    private static void installHook(String name, HookInstaller installer) {
        try {
            XC_MethodHook.Unhook unhook = installer.install();
            if (unhook == null) {
                throw new IllegalStateException("Hook returned no Unhook handle");
            }
            FEATURE_HOOKS.add(unhook);
            XposedBridge.log("MT8163 Tools: Protection installed " + name);
        } catch (Throwable error) {
            featureHookInstallFailed = true;
            XposedBridge.log("MT8163 Tools: Protection failed to install "
                    + name + ": " + error);
        }
    }

    private static void unhookAll(List<XC_MethodHook.Unhook> hooks, String type) {
        for (int i = hooks.size() - 1; i >= 0; i--) {
            try {
                hooks.get(i).unhook();
            } catch (Throwable error) {
                XposedBridge.log("MT8163 Tools: Protection " + type
                        + " unhook failed: " + error);
            }
        }
    }

    private interface HookInstaller {
        XC_MethodHook.Unhook install() throws Throwable;
    }

    private enum ProtectionType {
        KEEP,
        HIDE,
        CLEANER
    }

    private static final class TaskIdentity {
        final String packageName;
        final int taskId;

        TaskIdentity(String packageName, int taskId) {
            this.packageName = packageName;
            this.taskId = taskId;
        }
    }

    private static final class CleanerState {
        boolean protectCurrentTask;
        int taskId = -1;
        String packageName;
    }
}
