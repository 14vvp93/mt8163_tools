package com.mt8163.tools.modules.protection;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private static final int RED = Color.rgb(255, 0, 0);
    private static final int PRIMARY = Color.rgb(241, 242, 244);
    private static final int SECONDARY = Color.rgb(174, 178, 186);
    private static final int MUTED = Color.rgb(133, 138, 148);
    private static final int PANEL = Color.rgb(27, 29, 34);
    private static final int LINE = Color.rgb(52, 58, 64);
    private static final int MAX_PICKER_RESULTS = 10;

    private final Activity activity;
    private final PackageManager packageManager;
    private final LinearLayout applicationsContainer;

    public ProtectionModuleView(View root, Activity activity) {
        this.activity = activity;
        packageManager = activity.getPackageManager();
        applicationsContainer = root.findViewById(R.id.protection_applications);
        Button addButton = root.findViewById(R.id.protection_add_application);
        addButton.setOnClickListener(view -> showApplicationPicker());
        renderProtectedApplications();
    }

    private void renderProtectedApplications() {
        applicationsContainer.removeAllViews();
        List<AppEntry> configured = new ArrayList<>();

        for (String packageName : ProtectionSettingsStore.getConfiguredPackageNames(activity)) {
            try {
                ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
                configured.add(new AppEntry(info, getApplicationLabel(info)));
            } catch (PackageManager.NameNotFoundException ignored) {
            } catch (RuntimeException ignored) {
            }
        }

        sortApplications(configured);
        for (AppEntry app : configured) {
            applicationsContainer.addView(createProtectedApplicationCard(app));
        }

        if (configured.isEmpty()) {
            TextView empty = createText("No applications added yet", 14, MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(14), 0, dp(8));
            applicationsContainer.addView(empty);
        }
    }

    private View createProtectedApplicationCard(AppEntry app) {
        ProtectionConfig.ApplicationSettings settings =
                ProtectionSettingsStore.getApplicationSettings(activity, app.info.packageName);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(8), dp(10));
        card.setMinimumHeight(dp(66));
        card.setBackgroundResource(R.drawable.module_card_background);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(8);
        card.setLayoutParams(cardParams);

        ImageView appIcon = new ImageView(activity);
        appIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try {
            appIcon.setImageDrawable(packageManager.getApplicationIcon(app.info));
        } catch (RuntimeException error) {
            appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        card.addView(appIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView name = createText(app.label, 16, PRIMARY);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        nameParams.leftMargin = dp(12);
        nameParams.rightMargin = dp(8);
        card.addView(name, nameParams);

        LinearLayout indicators = new LinearLayout(activity);
        indicators.setGravity(Gravity.CENTER);
        indicators.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(indicators, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));

        addIndicator(indicators, android.R.drawable.ic_lock_lock,
                settings.shouldProtectCleaner(), "Protected from Memory Cleaner");
        addHiddenIndicator(indicators, settings.shouldHideRecents());
        addIndicator(indicators, android.R.drawable.ic_menu_close_clear_cancel,
                settings.shouldAllowCloseViaX(), "Allow closing the card via X");

        View separator = new View(activity);
        separator.setBackgroundColor(LINE);
        LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(dp(1), dp(28));
        separatorParams.leftMargin = dp(8);
        separatorParams.rightMargin = dp(6);
        card.addView(separator, separatorParams);

        card.addView(createActionButton(android.R.drawable.ic_menu_manage,
                "Application settings", () -> showApplicationSettings(app)));

        card.addView(createActionButton(android.R.drawable.ic_menu_delete,
                "Remove application", () -> confirmRemoveApplication(app)));

        return card;
    }

    private void addHiddenIndicator(LinearLayout parent, boolean enabled) {
        int tint = enabled ? RED : MUTED;
        FrameLayout indicator = new FrameLayout(activity);
        indicator.setContentDescription("Hide application card in Recents");

        ImageView eye = new ImageView(activity);
        eye.setImageResource(android.R.drawable.ic_menu_view);
        eye.setImageTintList(ColorStateList.valueOf(tint));
        eye.setScaleType(ImageView.ScaleType.FIT_CENTER);
        indicator.addView(eye, new FrameLayout.LayoutParams(
                dp(20), dp(20), Gravity.CENTER));

        View slash = new View(activity);
        slash.setBackgroundColor(tint);
        slash.setRotation(45f);
        indicator.addView(slash, new FrameLayout.LayoutParams(
                dp(2), dp(26), Gravity.CENTER));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(30), dp(30));
        params.leftMargin = dp(5);
        params.rightMargin = dp(5);
        parent.addView(indicator, params);
    }

    private void addIndicator(LinearLayout parent, int icon, boolean enabled, String description) {
        ImageView indicator = new ImageView(activity);
        indicator.setImageResource(icon);
        indicator.setImageTintList(ColorStateList.valueOf(enabled ? RED : MUTED));
        indicator.setContentDescription(description);
        indicator.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(20), dp(20));
        params.leftMargin = dp(5);
        params.rightMargin = dp(5);
        parent.addView(indicator, params);
    }

    private View createActionButton(int icon, String description, Runnable action) {
        FrameLayout button = new FrameLayout(activity);
        button.setBackgroundResource(android.R.drawable.list_selector_background);
        button.setContentDescription(description);
        button.setFocusable(true);
        button.setClickable(true);
        button.setMinimumWidth(dp(40));
        button.setMinimumHeight(dp(40));
        button.setOnClickListener(view -> action.run());

        ImageView image = new ImageView(activity);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(SECONDARY));
        image.setScaleType(ImageView.ScaleType.CENTER);
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                dp(22), dp(22), Gravity.CENTER);
        button.addView(image, imageParams);

        return button;
    }

    private void showApplicationSettings(AppEntry app) {
        ProtectionConfig.ApplicationSettings current =
                ProtectionSettingsStore.getApplicationSettings(activity, app.info.packageName);

        LinearLayout options = new LinearLayout(activity);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(4), dp(4), dp(4), dp(4));

        CheckBox cleaner = createSettingOption(
                "Protect from Memory Cleaner", current.shouldProtectCleaner());
        CheckBox hide = createSettingOption(
                "Hide application card in Recents", current.shouldHideRecents());
        CheckBox allowClose = createSettingOption(
                "Allow closing the card via X", current.shouldAllowCloseViaX());

        options.addView(cleaner);
        options.addView(hide);
        options.addView(allowClose);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(app.label)
                .setView(options)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    boolean saved = ProtectionSettingsStore.setApplicationSettings(
                            activity, app.info.packageName, hide.isChecked(),
                            cleaner.isChecked(), allowClose.isChecked());
                    if (!saved) {
                        showToast("Unable to save Protection settings");
                        return;
                    }
                    dialog.dismiss();
                    renderProtectedApplications();
                }));
        dialog.show();
    }

    private CheckBox createSettingOption(String title, boolean checked) {
        CheckBox option = new CheckBox(activity);
        option.setText(title);
        option.setTextColor(PRIMARY);
        option.setTextSize(15);
        option.setMinHeight(dp(52));
        option.setPadding(dp(4), dp(4), dp(4), dp(4));
        option.setButtonTintList(ColorStateList.valueOf(RED));
        option.setChecked(checked);
        return option;
    }

    private void confirmRemoveApplication(AppEntry app) {
        new AlertDialog.Builder(activity)
                .setTitle("Remove application?")
                .setMessage("Remove " + app.label + " from the Protection list?")
                .setPositiveButton("Remove", (dialog, which) -> {
                    if (ProtectionSettingsStore.removeApplicationSettings(
                            activity, app.info.packageName)) {
                        renderProtectedApplications();
                    } else {
                        showToast("Unable to remove application");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showApplicationPicker() {
        List<AppEntry> installed = loadInstalledApplications();
        Dialog dialog = new Dialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setBackground(createPanelBackground());

        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = createText("Add application", 19, PRIMARY);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView close = createText("×", 28, SECONDARY);
        close.setGravity(Gravity.CENTER);
        close.setBackgroundResource(android.R.drawable.list_selector_background);
        close.setContentDescription("Close");
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        close.setOnClickListener(view -> dialog.dismiss());
        root.addView(titleRow);

        EditText search = new EditText(activity);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(PRIMARY);
        search.setHintTextColor(MUTED);
        search.setHint("Search applications");
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(createSearchBackground(false));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        searchParams.topMargin = dp(12);
        root.addView(search, searchParams);

        CheckBox systemApps = createSettingOption("Display system applications", false);
        LinearLayout.LayoutParams systemParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        systemParams.topMargin = dp(2);
        root.addView(systemApps, systemParams);

        TextView results = createText("", 12, MUTED);
        LinearLayout.LayoutParams resultsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        resultsParams.topMargin = dp(2);
        resultsParams.bottomMargin = dp(6);
        root.addView(results, resultsParams);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(scroll, scrollParams);

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);

        Runnable renderPicker = () -> renderPickerResults(
                installed, search.getText().toString(), systemApps.isChecked(),
                list, results, dialog);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                renderPicker.run();
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        search.setOnFocusChangeListener((view, focused) ->
                search.setBackground(createSearchBackground(focused)));
        systemApps.setOnCheckedChangeListener((button, checked) -> renderPicker.run());

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            int maxWidth = activity.getResources().getDisplayMetrics().widthPixels - dp(48);
            int maxHeight = activity.getResources().getDisplayMetrics().heightPixels - dp(48);
            window.setLayout(Math.min(dp(640), maxWidth), Math.min(dp(430), maxHeight));
        }
        renderPicker.run();
    }

    private void renderPickerResults(List<AppEntry> installed, String query,
                                     boolean showSystem, LinearLayout list, TextView results,
                                     Dialog dialog) {
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
            if (!filter.isEmpty() && !label.contains(filter) && !packageName.contains(filter)) {
                continue;
            }

            matches++;
            if (shown >= MAX_PICKER_RESULTS) continue;
            list.addView(createPickerRow(app, dialog));
            shown++;
        }

        results.setText(matches > MAX_PICKER_RESULTS
                ? "Showing " + shown + " of " + matches + " matching applications"
                : matches + " matching applications");

        if (shown == 0) {
            TextView empty = createText(filter.isEmpty()
                    ? "No available applications found"
                    : "No applications match your search", 14, MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, dp(24));
            list.addView(empty);
        }
    }

    private View createPickerRow(AppEntry app, Dialog dialog) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setMinimumHeight(dp(60));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setClickable(true);
        row.setFocusable(true);

        ImageView icon = new ImageView(activity);
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(app.info));
        } catch (RuntimeException error) {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        textParams.leftMargin = dp(12);
        textParams.rightMargin = dp(8);
        row.addView(text, textParams);

        TextView label = createText(app.label, 15, PRIMARY);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(label);

        TextView packageName = createText(app.info.packageName, 11, MUTED);
        packageName.setSingleLine(true);
        packageName.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(packageName);

        ImageView add = new ImageView(activity);
        add.setImageResource(android.R.drawable.ic_input_add);
        add.setImageTintList(ColorStateList.valueOf(SECONDARY));
        row.addView(add, new LinearLayout.LayoutParams(dp(22), dp(22)));

        row.setOnClickListener(view -> {
            boolean saved = ProtectionSettingsStore.setApplicationSettings(
                    activity, app.info.packageName, false, false, false);
            if (!saved) {
                showToast("Unable to add application");
                return;
            }
            dialog.dismiss();
            renderProtectedApplications();
        });
        return row;
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
            showToast("Unable to load installed applications");
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

    private void sortApplications(List<AppEntry> applications) {
        Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(applications, (left, right) -> {
            int result = collator.compare(left.label, right.label);
            return result != 0 ? result
                    : left.info.packageName.compareTo(right.info.packageName);
        });
    }

    private TextView createText(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private GradientDrawable createPanelBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(PANEL);
        background.setCornerRadius(dp(6));
        background.setStroke(dp(1), LINE);
        return background;
    }

    private GradientDrawable createSearchBackground(boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(21, 23, 26));
        background.setCornerRadius(dp(6));
        background.setStroke(dp(focused ? 2 : 1), focused ? RED : LINE);
        return background;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void showToast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
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
