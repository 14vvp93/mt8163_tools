package com.mt8163.tools.modules.design;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.mt8163.tools.R;
import com.mt8163.tools.core.design.DesignStore;

import java.util.Locale;

public final class DesignModuleView {
    private final View root;
    private final DesignStore store;
    private final LinearLayout controls;

    private static final ColorSetting[] COLORS = {
        new ColorSetting("App background", "appColor", R.color.app_background),
        new ColorSetting("Sidebar", "sidebarColor", R.color.sidebar_background),
        new ColorSetting("Header", "headerColor", R.color.header_background),
        new ColorSetting("Surface", "surfaceColor", R.color.panel_background),
        new ColorSetting("Dialog", "dialogColor", R.color.dialog_background),
        new ColorSetting("Accent", "accentColor", R.color.neon_red),
        new ColorSetting("Primary text", "primaryTextColor", R.color.text_primary),
        new ColorSetting("Secondary text", "secondaryTextColor", R.color.text_secondary),
        new ColorSetting("Muted text", "mutedTextColor", R.color.text_muted),
        new ColorSetting("Divider and border", "lineColor", R.color.line),
        new ColorSetting("Button", "buttonColor", R.color.button_background),
        new ColorSetting("Button pressed", "buttonPressedColor", R.color.button_pressed_background),
        new ColorSetting("Button border", "buttonStrokeColor", R.color.button_stroke),
        new ColorSetting("Disabled button", "buttonDisabledColor", R.color.button_disabled),
        new ColorSetting("Sidebar icon surface", "iconSurfaceColor", R.color.sidebar_icon_background),
        new ColorSetting("Active status", "statusActiveColor", R.color.module_status_active),
        new ColorSetting("Inactive status", "statusInactiveColor", R.color.module_status_inactive),
        new ColorSetting("Switch thumb", "switchThumbColor", R.color.switch_thumb),
        new ColorSetting("Switch track", "switchTrackColor", R.color.switch_track_default),
        new ColorSetting("Switch checked", "switchCheckedColor", R.color.switch_track_checked),
        new ColorSetting("Switch disabled", "switchDisabledColor", R.color.switch_track_disabled)
    };

    private static final NumberSetting[] NUMBERS = {
        new NumberSetting("Spacing scale", "spacingScale", 0.50f, 1.50f, 100, 1f, "x"),
        new NumberSetting("Element scale", "sizeScale", 0.60f, 1.40f, 80, 1f, "x"),
        new NumberSetting("Text scale", "textScale", 0.70f, 1.50f, 80, 1f, "x"),
        new NumberSetting("Icon scale", "iconScale", 0.60f, 1.60f, 100, 1f, "x"),
        new NumberSetting("Corner radius", "cornerRadiusDp", 0f, 24f, 48, 8f, " dp"),
        new NumberSetting("Sidebar width", "sidebarWidthDp", 180f, 340f, 160, 260f, " dp"),
        new NumberSetting("Header height", "headerHeightDp", 56f, 112f, 56, 80f, " dp"),
        new NumberSetting("Card padding", "cardPaddingDp", 4f, 32f, 56, 16f, " dp"),
        new NumberSetting("Page padding", "pagePaddingDp", 4f, 40f, 72, 24f, " dp"),
        new NumberSetting("Button height", "buttonHeightDp", 32f, 64f, 64, 48f, " dp"),
        new NumberSetting("Icon size", "iconSizeDp", 16f, 48f, 64, 24f, " dp"),
        new NumberSetting("Border width", "borderWidthDp", 0f, 4f, 40, 1f, " dp"),
        new NumberSetting("Brand header height", "brandHeaderHeightDp", 48f, 100f, 52, 72f, " dp"),
        new NumberSetting("Brand mark size", "brandMarkSizeDp", 28f, 56f, 56, 44f, " dp"),
        new NumberSetting("Sidebar item height", "sidebarItemHeightDp", 44f, 80f, 72, 60f, " dp"),
        new NumberSetting("Sidebar item padding", "sidebarItemPaddingDp", 4f, 24f, 40, 12f, " dp"),
        new NumberSetting("Sidebar icon padding", "sidebarIconPaddingDp", 2f, 16f, 28, 8f, " dp"),
        new NumberSetting("Status indicator size", "statusDotSizeDp", 4f, 16f, 24, 8f, " dp"),
        new NumberSetting("Button minimum width", "buttonMinWidthDp", 80f, 180f, 100, 120f, " dp"),
        new NumberSetting("Switch minimum width", "switchMinWidthDp", 48f, 100f, 52, 72f, " dp")
    };

    public DesignModuleView(View root, DesignStore store) {
        this.root = root;
        this.store = store;
        controls = root.findViewById(R.id.design_controls);
        buildColors();
        buildNumbers();
        root.findViewById(R.id.design_reset_all).setOnClickListener(view -> reset());
    }

    private void buildColors() {
        addSection("GLOBAL COLORS");
        LinearLayout row = null;
        for (int i = 0; i < COLORS.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(root.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            ColorSetting setting = COLORS[i];
            LinearLayout cell = new LinearLayout(root.getContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(4), dp(4), dp(4), dp(4));
            TextView label = label(setting.label);
            EditText input = new EditText(root.getContext());
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setTextSize(13);
            input.setHint("#AARRGGBB");
            input.setText(colorText(store.getColor(setting.key, root.getContext().getColor(setting.resource))));
            input.setPadding(dp(8), 0, dp(8), 0);
            input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    root.getContext().getColor(R.color.line)));
            input.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String value = s.toString().trim();
                    if (value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) {
                        try { store.setColor(setting.key, Color.parseColor(value)); }
                        catch (IllegalArgumentException ignored) { }
                    }
                }
                @Override public void afterTextChanged(android.text.Editable s) { }
            });
            cell.addView(label);
            cell.addView(input, new LinearLayout.LayoutParams(-1, dp(42)));
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
    }

    private void buildNumbers() {
        addSection("GLOBAL DIMENSIONS");
        for (NumberSetting setting : NUMBERS) {
            float value = store.getFloat(setting.key, setting.defaultValue);
            LinearLayout row = new LinearLayout(root.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));
            LinearLayout heading = new LinearLayout(root.getContext());
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label(setting.label);
            TextView current = label(format(setting, value));
            heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            heading.addView(current);
            SeekBar seek = new SeekBar(root.getContext());
            seek.setMax(setting.steps);
            seek.setProgress(progress(setting, value));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    float next = setting.min + (setting.max - setting.min) * progress / setting.steps;
                    next = Math.round(next * 100f) / 100f;
                    store.setFloat(setting.key, next);
                    current.setText(format(setting, next));
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            row.addView(heading);
            row.addView(seek, new LinearLayout.LayoutParams(-1, dp(28)));
            addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void addSection(String title) {
        TextView view = label(title);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(12), 0, dp(8));
        addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView label(String value) {
        TextView view = new TextView(root.getContext());
        view.setText(value);
        view.setTextColor(root.getContext().getColor(R.color.text_primary));
        view.setTextSize(13);
        return view;
    }

    private void addView(View view, LinearLayout.LayoutParams params) {
        controls.addView(view, params);
    }

    private void reset() {
        store.reset();
        controls.removeAllViews();
        buildColors();
        buildNumbers();
    }

    private int progress(NumberSetting setting, float value) {
        return Math.max(0, Math.min(setting.steps,
                Math.round((value - setting.min) * setting.steps / (setting.max - setting.min))));
    }

    private String format(NumberSetting setting, float value) {
        if (setting.unit.equals("x")) return String.format(Locale.US, "%.2fx", value);
        return String.format(Locale.US, "%.0f%s", value, setting.unit);
    }

    private String colorText(int color) {
        return String.format(Locale.US, "#%08X", color);
    }

    private int dp(float value) {
        return Math.round(value * root.getResources().getDisplayMetrics().density);
    }

    private static final class ColorSetting {
        final String label, key;
        final int resource;
        ColorSetting(String label, String key, int resource) {
            this.label = label; this.key = key; this.resource = resource;
        }
    }

    private static final class NumberSetting {
        final String label, key, unit;
        final float min, max, defaultValue;
        final int steps;
        NumberSetting(String label, String key, float min, float max, int steps,
                      float defaultValue, String unit) {
            this.label = label; this.key = key; this.min = min; this.max = max;
            this.steps = steps; this.defaultValue = defaultValue; this.unit = unit;
        }
    }
}
