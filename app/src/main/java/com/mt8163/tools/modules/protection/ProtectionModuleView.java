package com.mt8163.tools.modules.protection;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
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
import java.util.List;
import java.util.Locale;

public final class ProtectionModuleView {
    private final Context context;
    private final PackageManager packageManager;
    private final LinearLayout applicationsContainer;
    private final TextView resultsView;
    private final EditText searchView;
    private final List<AppEntry> applications = new ArrayList<>();

    public ProtectionModuleView(View root, Context context) {
        this.context = context.getApplicationContext();
        packageManager = this.context.getPackageManager();
        applicationsContainer = root.findViewById(R.id.protection_applications);
        resultsView = root.findViewById(R.id.protection_results);
        searchView = root.findViewById(R.id.protection_search);

        loadApplications();
        searchView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                render(text == null ? "" : text.toString());
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        render("");
    }

    private void loadApplications() {
        try {
            List<ApplicationInfo> installed =
                    packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo info : installed) {
                if (info == null || info.packageName == null
                        || context.getPackageName().equals(info.packageName)
                        || (info.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
                    continue;
                }

                if (packageManager.getLaunchIntentForPackage(info.packageName) == null) {
                    continue;
                }

                String label;
                try {
                    label = packageManager.getApplicationLabel(info).toString();
                } catch (RuntimeException error) {
                    label = info.packageName;
                }

                applications.add(new AppEntry(info, label));
            }
        } catch (RuntimeException error) {
            Toast.makeText(context, "Unable to load installed applications",
                    Toast.LENGTH_LONG).show();
        }

        Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(applications, (left, right) -> {
            int result = collator.compare(left.label, right.label);
            return result != 0 ? result
                    : left.info.packageName.compareTo(right.info.packageName);
        });
    }

    private void render(String query) {
        String filter = query.trim().toLowerCase(Locale.ROOT);
        applicationsContainer.removeAllViews();
        int shown = 0;

        for (AppEntry app : applications) {
            if (!filter.isEmpty()
                    && !app.label.toLowerCase(Locale.ROOT).contains(filter)
                    && !app.info.packageName.toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }

            applicationsContainer.addView(createApplicationCard(app));
            shown++;
        }

        resultsView.setText(shown + " of " + applications.size() + " applications");
        if (shown == 0) {
            TextView empty = new TextView(context);
            empty.setText(filter.isEmpty()
                    ? "No launchable applications found"
                    : "No applications match your search");
            empty.setTextColor(Color.parseColor("#858A94"));
            empty.setTextSize(15);
            empty.setPadding(dp(4), dp(18), dp(4), dp(18));
            applicationsContainer.addView(empty);
        }
    }

    private View createApplicationCard(AppEntry app) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(12));
        card.setBackgroundResource(R.drawable.module_card_background);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);

        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setOrientation(LinearLayout.HORIZONTAL);

        ImageView icon = new ImageView(context);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconParams.rightMargin = dp(14);
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(app.info));
        } catch (RuntimeException ignored) {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        heading.addView(icon, iconParams);

        LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView label = new TextView(context);
        label.setText(app.label);
        label.setTextColor(Color.parseColor("#F1F2F4"));
        label.setTextSize(16);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        names.addView(label);

        TextView packageName = new TextView(context);
        packageName.setText(app.info.packageName);
        packageName.setTextColor(Color.parseColor("#858A94"));
        packageName.setTextSize(12);
        packageName.setSingleLine(true);
        packageName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(packageName);
        heading.addView(names);
        card.addView(heading);

        ProtectionConfig.ApplicationSettings saved =
                ProtectionSettingsStore.getApplicationSettings(
                        context, app.info.packageName);

        CheckBox keep = createOption("Keep", saved.shouldKeepRecents());
        CheckBox hide = createOption("Hide", saved.shouldHideRecents());
        CheckBox cleaner = createOption("Memory Cleaner", saved.shouldProtectCleaner());
        CheckBox allowCloseViaX = createOption(
                "Allow close via X", saved.shouldAllowCloseViaX());

        LinearLayout options = new LinearLayout(context);
        options.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        optionsParams.topMargin = dp(8);
        card.addView(options, optionsParams);

        LinearLayout firstRow = new LinearLayout(context);
        firstRow.setGravity(Gravity.CENTER_VERTICAL);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        options.addView(firstRow);
        addOption(firstRow, keep);
        addOption(firstRow, hide);

        LinearLayout secondRow = new LinearLayout(context);
        secondRow.setGravity(Gravity.CENTER_VERTICAL);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        options.addView(secondRow);
        addOption(secondRow, cleaner);
        addOption(secondRow, allowCloseViaX);

        boolean[] updating = {false};
        keep.setOnCheckedChangeListener((button, checked) -> {
            if (updating[0]) return;
            updating[0] = true;
            if (checked) hide.setChecked(false);
            updating[0] = false;
            save(app, keep, hide, cleaner, allowCloseViaX);
        });

        hide.setOnCheckedChangeListener((button, checked) -> {
            if (updating[0]) return;
            updating[0] = true;
            if (checked) keep.setChecked(false);
            updating[0] = false;
            save(app, keep, hide, cleaner, allowCloseViaX);
        });

        cleaner.setOnCheckedChangeListener((button, checked) ->
                save(app, keep, hide, cleaner, allowCloseViaX));

        allowCloseViaX.setOnCheckedChangeListener((button, checked) ->
                save(app, keep, hide, cleaner, allowCloseViaX));

        return card;
    }

    private CheckBox createOption(String title, boolean checked) {
        CheckBox option = new CheckBox(context);
        option.setText(title);
        option.setTextColor(Color.parseColor("#AEB2BA"));
        option.setTextSize(13);
        option.setMinHeight(dp(48));
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setChecked(checked);
        return option;
    }

    private void addOption(LinearLayout parent, CheckBox option) {
        parent.addView(option, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }

    private void save(AppEntry app, CheckBox keep, CheckBox hide, CheckBox cleaner,
                      CheckBox allowCloseViaX) {
        boolean saved = ProtectionSettingsStore.setApplicationSettings(
                context, app.info.packageName,
                keep.isChecked(), hide.isChecked(), cleaner.isChecked(),
                allowCloseViaX.isChecked());
        if (!saved) {
            Toast.makeText(context, "Unable to save Protection settings",
                    Toast.LENGTH_SHORT).show();
            render(searchView.getText().toString());
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
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
