package com.mt8163.tools.modules.protection;

import android.app.Activity;
import android.app.Dialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.mt8163.tools.R;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ProtectionModuleView {
    private static final int MAX_PICKER_RESULTS = 10;

    private final Activity activity;
    private final PackageManager packageManager;
    private final LayoutInflater inflater;
    private final LinearLayout applicationsContainer;

    public ProtectionModuleView(View root, Activity activity) {
        this.activity = activity;
        packageManager = activity.getPackageManager();
        inflater = LayoutInflater.from(activity);
        applicationsContainer = root.findViewById(R.id.protection_applications);
        root.findViewById(R.id.protection_add_application)
                .setOnClickListener(view -> showApplicationPicker());
        renderProtectedApplications();
    }

    private void renderProtectedApplications() {
        applicationsContainer.removeAllViews();
        List<AppEntry> configured = new ArrayList<>();

        for (String packageName : ProtectionSettingsStore.getConfiguredPackageNames(activity)) {
            try {
                ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
                configured.add(new AppEntry(info, getApplicationLabel(info)));
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
        }

        sortApplications(configured);
        for (AppEntry app : configured) {
            addProtectedApplication(app);
        }

        if (configured.isEmpty()) {
            applicationsContainer.addView(inflater.inflate(
                    R.layout.item_protection_empty, applicationsContainer, false));
        }
    }

    private void addProtectedApplication(AppEntry app) {
        View card = inflater.inflate(R.layout.item_protected_application,
                applicationsContainer, false);
        ProtectionConfig.ApplicationSettings settings =
                ProtectionSettingsStore.getApplicationSettings(activity, app.info.packageName);

        ImageView icon = card.findViewById(R.id.protection_app_icon);
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(app.info));
        } catch (RuntimeException error) {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        TextView name = card.findViewById(R.id.protection_app_name);
        name.setText(app.label);

        setIndicatorState(card, R.id.protection_indicator_cleaner,
                settings.shouldProtectCleaner());
        setIndicatorState(card, R.id.protection_indicator_hide,
                settings.shouldHideRecents());
        setIndicatorState(card, R.id.protection_indicator_close,
                settings.shouldAllowCloseViaX());

        card.findViewById(R.id.protection_app_settings)
                .setOnClickListener(view -> showApplicationSettings(app));
        card.findViewById(R.id.protection_app_remove)
                .setOnClickListener(view -> confirmRemoveApplication(app));
        applicationsContainer.addView(card);
    }

    private static void setIndicatorState(View root, int id, boolean enabled) {
        root.findViewById(id).setActivated(enabled);
    }

    private void showApplicationSettings(AppEntry app) {
        ProtectionConfig.ApplicationSettings current =
                ProtectionSettingsStore.getApplicationSettings(activity, app.info.packageName);

        Dialog dialog = new Dialog(activity);
        View root = inflater.inflate(R.layout.dialog_protection_settings, null, false);
        TextView title = root.findViewById(R.id.protection_settings_title);
        CheckBox cleaner = root.findViewById(R.id.protection_setting_cleaner);
        CheckBox hide = root.findViewById(R.id.protection_setting_hide);
        CheckBox allowClose = root.findViewById(R.id.protection_setting_allow_close);
        Button save = root.findViewById(R.id.protection_settings_save);
        Button cancel = root.findViewById(R.id.protection_settings_cancel);

        title.setText(app.label);
        cleaner.setChecked(current.shouldProtectCleaner());
        hide.setChecked(current.shouldHideRecents());
        allowClose.setChecked(current.shouldAllowCloseViaX());

        save.setOnClickListener(view -> {
            boolean saved = ProtectionSettingsStore.setApplicationSettings(
                    activity, app.info.packageName, hide.isChecked(),
                    cleaner.isChecked(), allowClose.isChecked());
            if (!saved) {
                showToast(R.string.protection_save_failed);
                return;
            }
            dialog.dismiss();
            renderProtectedApplications();
        });
        cancel.setOnClickListener(view -> dialog.dismiss());

        showDialog(dialog, root, R.dimen.protection_settings_dialog_width,
                R.dimen.protection_settings_dialog_height);
    }

    private void confirmRemoveApplication(AppEntry app) {
        Dialog dialog = new Dialog(activity);
        View root = inflater.inflate(R.layout.dialog_protection_confirm, null, false);
        TextView message = root.findViewById(R.id.protection_confirm_message);
        Button remove = root.findViewById(R.id.protection_confirm_remove);
        Button cancel = root.findViewById(R.id.protection_confirm_cancel);

        message.setText(activity.getString(R.string.protection_remove_message, app.label));
        remove.setOnClickListener(view -> {
            if (ProtectionSettingsStore.removeApplicationSettings(
                    activity, app.info.packageName)) {
                dialog.dismiss();
                renderProtectedApplications();
            } else {
                showToast(R.string.protection_remove_failed);
            }
        });
        cancel.setOnClickListener(view -> dialog.dismiss());

        showDialog(dialog, root, R.dimen.protection_settings_dialog_width,
                R.dimen.protection_confirm_dialog_height);
    }

    private void showApplicationPicker() {
        List<AppEntry> installed = loadInstalledApplications();
        Dialog dialog = new Dialog(activity);
        View root = inflater.inflate(R.layout.dialog_protection_picker, null, false);

        EditText search = root.findViewById(R.id.protection_picker_search);
        CheckBox systemApps = root.findViewById(R.id.protection_picker_system_apps);
        TextView results = root.findViewById(R.id.protection_picker_results);
        LinearLayout list = root.findViewById(R.id.protection_picker_list);

        root.findViewById(R.id.protection_picker_close)
                .setOnClickListener(view -> dialog.dismiss());

        Runnable render = () -> renderPickerResults(installed,
                search.getText().toString(), systemApps.isChecked(), list, results, dialog);

        search.addTextChangedListener(new TextWatcher() {
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
        systemApps.setOnCheckedChangeListener((button, checked) -> render.run());

        showDialog(dialog, root, R.dimen.picker_dialog_max_width,
                R.dimen.picker_dialog_max_height);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        render.run();
    }

    private void renderPickerResults(List<AppEntry> installed, String query,
                                     boolean showSystem, LinearLayout list,
                                     TextView results, Dialog dialog) {
        list.removeAllViews();
        String filter = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Set<String> configured = new HashSet<>(
                ProtectionSettingsStore.getConfiguredPackageNames(activity));
        int matches = 0;
        int shown = 0;

        for (AppEntry app : installed) {
            if (configured.contains(app.info.packageName)) continue;
            if (!showSystem && isSystemApplication(app.info)) continue;

            String label = app.label.toLowerCase(Locale.ROOT);
            String packageName = app.info.packageName.toLowerCase(Locale.ROOT);
            if (!filter.isEmpty() && !label.contains(filter)
                    && !packageName.contains(filter)) continue;

            matches++;
            if (shown >= MAX_PICKER_RESULTS) continue;
            addPickerRow(app, list, dialog);
            shown++;
        }

        results.setText(activity.getResources().getQuantityString(
                R.plurals.protection_picker_results, matches, shown, matches));

        if (shown == 0) {
            TextView empty = (TextView) inflater.inflate(
                    R.layout.item_protection_empty, list, false);
            empty.setText(filter.isEmpty()
                    ? R.string.protection_picker_empty
                    : R.string.protection_picker_no_matches);
            list.addView(empty);
        }
    }

    private void addPickerRow(AppEntry app, LinearLayout list, Dialog dialog) {
        View row = inflater.inflate(R.layout.item_protection_picker, list, false);
        ImageView icon = row.findViewById(R.id.protection_picker_app_icon);
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(app.info));
        } catch (RuntimeException error) {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        ((TextView) row.findViewById(R.id.protection_picker_app_name)).setText(app.label);
        ((TextView) row.findViewById(R.id.protection_picker_package))
                .setText(app.info.packageName);

        row.setOnClickListener(view -> {
            boolean saved = ProtectionSettingsStore.setApplicationSettings(
                    activity, app.info.packageName, false, false, false);
            if (!saved) {
                showToast(R.string.protection_add_failed);
                return;
            }
            dialog.dismiss();
            renderProtectedApplications();
        });
        list.addView(row);
    }

    private void showDialog(Dialog dialog, View content, int widthResource,
                            int heightResource) {
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();

        Window window = dialog.getWindow();
        if (window == null) return;

        window.setBackgroundDrawableResource(R.drawable.dialog_window_background);
        int maxWidth = activity.getResources().getDisplayMetrics().widthPixels
                - activity.getResources().getDimensionPixelSize(R.dimen.button_height);
        int maxHeight = activity.getResources().getDisplayMetrics().heightPixels
                - activity.getResources().getDimensionPixelSize(R.dimen.button_height);
        int width = Math.min(activity.getResources().getDimensionPixelSize(widthResource), maxWidth);
        int height = Math.min(activity.getResources().getDimensionPixelSize(heightResource), maxHeight);
        window.setLayout(width, height);
    }

    private List<AppEntry> loadInstalledApplications() {
        List<AppEntry> result = new ArrayList<>();
        try {
            List<ApplicationInfo> installed =
                    packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo info : installed) {
                if (info == null || info.packageName == null
                        || activity.getPackageName().equals(info.packageName)
                        || packageManager.getLaunchIntentForPackage(info.packageName) == null) {
                    continue;
                }
                result.add(new AppEntry(info, getApplicationLabel(info)));
            }
        } catch (RuntimeException error) {
            showToast(R.string.protection_load_failed);
        }
        sortApplications(result);
        return result;
    }

    private String getApplicationLabel(ApplicationInfo info) {
        try {
            CharSequence label = packageManager.getApplicationLabel(info);
            return label == null ? info.packageName : label.toString();
        } catch (RuntimeException error) {
            return info.packageName;
        }
    }

    private boolean isSystemApplication(ApplicationInfo info) {
        return (info.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    private static void sortApplications(List<AppEntry> applications) {
        Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(applications, (left, right) -> {
            int result = collator.compare(left.label, right.label);
            return result != 0 ? result
                    : left.info.packageName.compareTo(right.info.packageName);
        });
    }

    private void showToast(int messageResource) {
        Toast.makeText(activity, messageResource, Toast.LENGTH_SHORT).show();
    }

    private static final class AppEntry {
        final ApplicationInfo info;
        final String label;

        AppEntry(ApplicationInfo info, String label) {
            this.info = info;
            this.label = label;
        }
    }
}
