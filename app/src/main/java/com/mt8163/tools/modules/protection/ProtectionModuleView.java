package com.mt8163.tools.modules.protection;

import android.app.Activity;
import android.app.Dialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ProtectionModuleView {
    private static final int MAX_PROTECTION_PICKER_RESULTS = 10;

    private final Activity protectionActivity;
    private final PackageManager protectionPackageManager;
    private final LayoutInflater protectionInflater;
    private final LinearLayout protectionApplicationsContainer;

    public ProtectionModuleView(View protectionRootView, Activity protectionActivity) {
        this.protectionActivity = protectionActivity;
        protectionPackageManager = protectionActivity.getPackageManager();
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
        try {
            icon.setImageDrawable(protectionPackageManager.getApplicationIcon(app.info));
        } catch (RuntimeException error) {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

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
        protectionRootView.findViewById(id).setActivated(enabled);
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
        List<ProtectedApplicationEntry> installed = loadInstalledApplications();
        Dialog protectionDialog = new Dialog(protectionActivity);
        View protectionRootView = protectionInflater.inflate(R.layout.dialog_protection_picker, null, false);

        EditText applicationSearchInput = protectionRootView.findViewById(R.id.protection_picker_search);
        Switch showSystemAppsSwitch = protectionRootView.findViewById(R.id.protection_picker_system_apps);
        TextView applicationPickerResultsText = protectionRootView.findViewById(R.id.protection_picker_results);
        LinearLayout applicationPickerList = protectionRootView.findViewById(R.id.protection_picker_list);

        protectionRootView.findViewById(R.id.protection_picker_close)
                .setOnClickListener(view -> protectionDialog.dismiss());

        Runnable render = () -> renderPickerResults(installed,
                applicationSearchInput.getText().toString(), showSystemAppsSwitch.isChecked(), applicationPickerList, applicationPickerResultsText, protectionDialog);

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
        render.run();
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
        try {
            icon.setImageDrawable(protectionPackageManager.getApplicationIcon(app.info));
        } catch (RuntimeException error) {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

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
        List<ProtectedApplicationEntry> result = new ArrayList<>();
        try {
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
        } catch (RuntimeException error) {
            showToast(R.string.protection_load_failed);
        }
        sortApplications(result);
        return result;
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

    private static final class ProtectedApplicationEntry {
        final ApplicationInfo info;
        final String label;

        ProtectedApplicationEntry(ApplicationInfo info, String label) {
            this.info = info;
            this.label = label;
        }
    }
}
