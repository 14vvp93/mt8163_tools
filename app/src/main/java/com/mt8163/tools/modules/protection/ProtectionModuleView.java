package com.mt8163.tools.modules.protection;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.mt8163.tools.R;
import com.mt8163.tools.ui.DialogHelper;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProtectionModuleView {
    private static final int MAX_PROTECTION_PICKER_RESULTS = 10;
    private static final Object APPLICATION_CACHE_LOCK = new Object();
    private static final ExecutorService APPLICATION_LOADER =
            Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static List<ProtectedApplicationEntry> installedApplicationsCache;
    private static long applicationCacheGeneration;
    private static final Map<String, Drawable.ConstantState> applicationIconCache = new HashMap<>();
    private static boolean packageReceiverRegistered;

    private final Activity protectionActivity;
    private final PackageManager protectionPackageManager;
    private final LayoutInflater protectionInflater;
    private final LinearLayout protectionApplicationsContainer;

    public ProtectionModuleView(View protectionRootView, Activity protectionActivity) {
        this.protectionActivity = protectionActivity;
        protectionPackageManager = protectionActivity.getPackageManager();
        registerPackageChangeReceiver(protectionActivity.getApplicationContext());
        protectionInflater = LayoutInflater.from(protectionActivity);
        protectionApplicationsContainer = protectionRootView.findViewById(R.id.protection_applications);
        protectionRootView.findViewById(R.id.protection_add_application)
                .setOnClickListener(view -> showApplicationPicker());
        renderProtectedApplications();
    }

    private void renderProtectedApplications() {
        protectionApplicationsContainer.removeAllViews();
        List<ProtectedApplicationEntry> configured = new ArrayList<>();

        for (String packageName : ProtectionSettingsStore.getConfiguredPackageNames(protectionActivity)) {
            try {
                ApplicationInfo info = protectionPackageManager.getApplicationInfo(packageName, 0);
                configured.add(new ProtectedApplicationEntry(info, getApplicationLabel(info)));
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
        }

        sortApplications(configured);
        for (ProtectedApplicationEntry app : configured) {
            addProtectedApplication(app);
        }

        if (configured.isEmpty()) {
            protectionApplicationsContainer.addView(protectionInflater.inflate(
                    R.layout.item_protection_empty, protectionApplicationsContainer, false));
        }
    }

    private void addProtectedApplication(ProtectedApplicationEntry app) {
        View card = protectionInflater.inflate(R.layout.item_protected_application,
                protectionApplicationsContainer, false);
        ProtectionConfig.ApplicationSettings applicationProtectionSettings =
                ProtectionSettingsStore.getApplicationSettings(protectionActivity, app.info.packageName);

        ImageView icon = card.findViewById(R.id.protection_app_icon);
        icon.setImageDrawable(getCachedApplicationIcon(app));

        TextView name = card.findViewById(R.id.protection_app_name);
        name.setText(app.label);

        setProtectionIndicatorState(card, R.id.protection_indicator_cleaner,
                applicationProtectionSettings.shouldProtectCleaner());
        setProtectionIndicatorState(card, R.id.protection_indicator_hide,
                applicationProtectionSettings.shouldHideRecents());
        setProtectionIndicatorState(card, R.id.protection_indicator_close,
                applicationProtectionSettings.shouldAllowCloseViaX());

        card.findViewById(R.id.protection_app_settings)
                .setOnClickListener(view -> showApplicationSettings(app));
        card.findViewById(R.id.protection_app_remove)
                .setOnClickListener(view -> confirmRemoveApplication(app));
        protectionApplicationsContainer.addView(card);
    }

    private static void setProtectionIndicatorState(View protectionRootView, int id, boolean enabled) {
        View indicator = protectionRootView.findViewById(id);
        indicator.setActivated(enabled);

        int enabledDescription;
        int disabledDescription;
        if (id == R.id.protection_indicator_cleaner) {
            enabledDescription = R.string.protection_indicator_cleaner_enabled;
            disabledDescription = R.string.protection_indicator_cleaner_disabled;
        } else if (id == R.id.protection_indicator_hide) {
            enabledDescription = R.string.protection_indicator_hide_enabled;
            disabledDescription = R.string.protection_indicator_hide_disabled;
        } else {
            enabledDescription = R.string.protection_indicator_close_enabled;
            disabledDescription = R.string.protection_indicator_close_disabled;
        }
        indicator.setContentDescription(protectionRootView.getContext().getString(
                enabled ? enabledDescription : disabledDescription));
    }

    private void showApplicationSettings(ProtectedApplicationEntry app) {
        ProtectionConfig.ApplicationSettings currentApplicationSettings =
                ProtectionSettingsStore.getApplicationSettings(protectionActivity, app.info.packageName);

        Dialog protectionDialog = new Dialog(protectionActivity);
        View protectionRootView = protectionInflater.inflate(R.layout.dialog_protection_settings, null, false);
        TextView title = protectionRootView.findViewById(R.id.protection_settings_title);
        Switch protectCleanerSwitch = protectionRootView.findViewById(R.id.protection_setting_cleaner);
        Switch hideRecentsSwitch = protectionRootView.findViewById(R.id.protection_setting_hide);
        Switch allowCloseViaXSwitch = protectionRootView.findViewById(R.id.protection_setting_allow_close);
        Button save = protectionRootView.findViewById(R.id.protection_settings_save);
        Button cancel = protectionRootView.findViewById(R.id.protection_settings_cancel);

        title.setText(app.label);
        protectCleanerSwitch.setChecked(currentApplicationSettings.shouldProtectCleaner());
        hideRecentsSwitch.setChecked(currentApplicationSettings.shouldHideRecents());
        allowCloseViaXSwitch.setChecked(currentApplicationSettings.shouldAllowCloseViaX());

        save.setOnClickListener(view -> {
            boolean saved = ProtectionSettingsStore.setApplicationSettings(
                    protectionActivity, app.info.packageName, hideRecentsSwitch.isChecked(),
                    protectCleanerSwitch.isChecked(), allowCloseViaXSwitch.isChecked());
            if (!saved) {
                showToast(R.string.protection_save_failed);
                return;
            }
            protectionDialog.dismiss();
            renderProtectedApplications();
        });
        cancel.setOnClickListener(view -> protectionDialog.dismiss());

        DialogHelper.showCompact(protectionActivity, protectionDialog, protectionRootView);
    }

    private void confirmRemoveApplication(ProtectedApplicationEntry app) {
        Dialog protectionDialog = new Dialog(protectionActivity);
        View protectionRootView = protectionInflater.inflate(R.layout.dialog_protection_confirm, null, false);
        TextView message = protectionRootView.findViewById(R.id.protection_confirm_message);
        Button remove = protectionRootView.findViewById(R.id.protection_confirm_remove);
        Button cancel = protectionRootView.findViewById(R.id.protection_confirm_cancel);

        message.setText(protectionActivity.getString(R.string.protection_remove_message, app.label));
        remove.setOnClickListener(view -> {
            if (ProtectionSettingsStore.removeApplicationSettings(
                    protectionActivity, app.info.packageName)) {
                protectionDialog.dismiss();
                renderProtectedApplications();
            } else {
                showToast(R.string.protection_remove_failed);
            }
        });
        cancel.setOnClickListener(view -> protectionDialog.dismiss());

        DialogHelper.showCompact(protectionActivity, protectionDialog, protectionRootView);
    }

    private void showApplicationPicker() {
        Dialog protectionDialog = new Dialog(protectionActivity);
        View protectionRootView = protectionInflater.inflate(R.layout.dialog_protection_picker, null, false);

        EditText applicationSearchInput = protectionRootView.findViewById(R.id.protection_picker_search);
        Switch showSystemAppsSwitch = protectionRootView.findViewById(R.id.protection_picker_system_apps);
        TextView applicationPickerResultsText = protectionRootView.findViewById(R.id.protection_picker_results);
        LinearLayout applicationPickerList = protectionRootView.findViewById(R.id.protection_picker_list);
        PickerData pickerData = new PickerData();

        protectionRootView.findViewById(R.id.protection_picker_close)
                .setOnClickListener(view -> protectionDialog.dismiss());
        applicationPickerResultsText.setText(R.string.protection_loading);

        Runnable render = () -> {
            if (!pickerData.loaded) return;
            renderPickerResults(pickerData.applications,
                    applicationSearchInput.getText().toString(),
                    showSystemAppsSwitch.isChecked(), applicationPickerList,
                    applicationPickerResultsText, protectionDialog);
        };

        applicationSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                render.run();
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        showSystemAppsSwitch.setOnCheckedChangeListener((button, checked) -> render.run());

        DialogHelper.showSelection(protectionActivity, protectionDialog, protectionRootView);
        APPLICATION_LOADER.execute(() -> {
            List<ProtectedApplicationEntry> installed = Collections.emptyList();
            boolean failed = false;
            try {
                installed = loadInstalledApplications();
            } catch (RuntimeException error) {
                failed = true;
            }

            final List<ProtectedApplicationEntry> result = installed;
            final boolean loadFailed = failed;
            MAIN_HANDLER.post(() -> {
                if (protectionActivity.isFinishing() || protectionActivity.isDestroyed()
                        || !protectionDialog.isShowing()) return;
                pickerData.applications = result;
                pickerData.loaded = true;
                if (loadFailed) showToast(R.string.protection_load_failed);
                render.run();
            });
        });
    }

    private void renderPickerResults(List<ProtectedApplicationEntry> installed, String query,
                                     boolean showSystem, LinearLayout applicationPickerList,
                                     TextView applicationPickerResultsText, Dialog protectionDialog) {
        applicationPickerList.removeAllViews();
        String filter = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Set<String> configured = new HashSet<>(
                ProtectionSettingsStore.getConfiguredPackageNames(protectionActivity));
        int matches = 0;
        int shown = 0;

        for (ProtectedApplicationEntry app : installed) {
            if (configured.contains(app.info.packageName)) continue;
            if (!showSystem && isSystemApplication(app.info)) continue;

            String label = app.label.toLowerCase(Locale.ROOT);
            String packageName = app.info.packageName.toLowerCase(Locale.ROOT);
            if (!filter.isEmpty() && !label.contains(filter)
                    && !packageName.contains(filter)) continue;

            matches++;
            if (shown >= MAX_PROTECTION_PICKER_RESULTS) continue;
            addPickerRow(app, applicationPickerList, protectionDialog);
            shown++;
        }

        applicationPickerResultsText.setText(protectionActivity.getResources().getQuantityString(
                R.plurals.protection_picker_results, matches, shown, matches));

        if (shown == 0) {
            TextView empty = (TextView) protectionInflater.inflate(
                    R.layout.item_protection_empty, applicationPickerList, false);
            empty.setText(filter.isEmpty()
                    ? R.string.protection_picker_empty
                    : R.string.protection_picker_no_matches);
            applicationPickerList.addView(empty);
        }
    }

    private void addPickerRow(ProtectedApplicationEntry app, LinearLayout applicationPickerList, Dialog protectionDialog) {
        View row = protectionInflater.inflate(R.layout.item_protection_picker, applicationPickerList, false);
        ImageView icon = row.findViewById(R.id.protection_picker_app_icon);
        icon.setImageDrawable(getCachedApplicationIcon(app));

        ((TextView) row.findViewById(R.id.protection_picker_app_name)).setText(app.label);
        ((TextView) row.findViewById(R.id.protection_picker_package))
                .setText(app.info.packageName);

        row.setOnClickListener(view -> {
            boolean saved = ProtectionSettingsStore.setApplicationSettings(
                    protectionActivity, app.info.packageName, false, false, false);
            if (!saved) {
                showToast(R.string.protection_add_failed);
                return;
            }
            protectionDialog.dismiss();
            renderProtectedApplications();
        });
        applicationPickerList.addView(row);
    }


    private List<ProtectedApplicationEntry> loadInstalledApplications() {
        final long cacheGeneration;
        synchronized (APPLICATION_CACHE_LOCK) {
            if (installedApplicationsCache != null) return installedApplicationsCache;
            cacheGeneration = applicationCacheGeneration;
        }

        List<ProtectedApplicationEntry> result = new ArrayList<>();
        List<ApplicationInfo> installed =
                protectionPackageManager.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo info : installed) {
            if (info == null || info.packageName == null
                    || protectionActivity.getPackageName().equals(info.packageName)
                    || protectionPackageManager.getLaunchIntentForPackage(info.packageName) == null) {
                continue;
            }
            result.add(new ProtectedApplicationEntry(info, getApplicationLabel(info)));
        }

        sortApplications(result);
        List<ProtectedApplicationEntry> cached = Collections.unmodifiableList(result);
        synchronized (APPLICATION_CACHE_LOCK) {
            if (cacheGeneration != applicationCacheGeneration) return cached;
            if (installedApplicationsCache == null) installedApplicationsCache = cached;
            return installedApplicationsCache;
        }
    }

    private void registerPackageChangeReceiver(Context context) {
        synchronized (APPLICATION_CACHE_LOCK) {
            if (packageReceiverRegistered) return;

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addDataScheme("package");

            context.registerReceiver(new PackageChangeReceiver(), filter);
            packageReceiverRegistered = true;
        }
    }

    private Drawable getCachedApplicationIcon(ProtectedApplicationEntry app) {
        synchronized (APPLICATION_CACHE_LOCK) {
            Drawable.ConstantState state = applicationIconCache.get(app.info.packageName);
            if (state != null) return state.newDrawable(protectionActivity.getResources()).mutate();
        }

        try {
            Drawable drawable = protectionPackageManager.getApplicationIcon(app.info);
            Drawable.ConstantState state = drawable.getConstantState();
            if (state != null) {
                synchronized (APPLICATION_CACHE_LOCK) {
                    applicationIconCache.put(app.info.packageName, state);
                }
                return state.newDrawable(protectionActivity.getResources()).mutate();
            }
            return drawable;
        } catch (RuntimeException error) {
            return protectionActivity.getDrawable(android.R.drawable.sym_def_app_icon);
        }
    }

    private String getApplicationLabel(ApplicationInfo info) {
        try {
            CharSequence label = protectionPackageManager.getApplicationLabel(info);
            return label == null ? info.packageName : label.toString();
        } catch (RuntimeException error) {
            return info.packageName;
        }
    }

    private boolean isSystemApplication(ApplicationInfo info) {
        return (info.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    private static void sortApplications(List<ProtectedApplicationEntry> applications) {
        Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(applications, (left, right) -> {
            int result = collator.compare(left.label, right.label);
            return result != 0 ? result
                    : left.info.packageName.compareTo(right.info.packageName);
        });
    }

    private void showToast(int messageResource) {
        Toast.makeText(protectionActivity, messageResource, Toast.LENGTH_SHORT).show();
    }

    private static final class PackageChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (APPLICATION_CACHE_LOCK) {
                applicationCacheGeneration++;
                installedApplicationsCache = null;
                applicationIconCache.clear();
            }
        }
    }

    private static final class PickerData {
        List<ProtectedApplicationEntry> applications = Collections.emptyList();
        boolean loaded;
    }

    private static final class ProtectedApplicationEntry {
        final ApplicationInfo info;
        final String label;

        ProtectedApplicationEntry(ApplicationInfo info, String label) {
            this.info = info;
            this.label = label;
        }
    }
}
