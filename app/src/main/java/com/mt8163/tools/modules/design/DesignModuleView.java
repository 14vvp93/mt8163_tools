package com.mt8163.tools.modules.design;

import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import com.mt8163.tools.R;
import com.mt8163.tools.core.design.DesignStore;

public final class DesignModuleView {
    private final View root;
    private final DesignStore store;
    private final EditText appColor;
    private final EditText sidebarColor;
    private final EditText headerColor;
    private final EditText surfaceColor;
    private final EditText accentColor;
    private final EditText primaryTextColor;
    private final EditText secondaryTextColor;
    private final SeekBar spacing;
    private final SeekBar size;
    private final SeekBar radius;
    private final TextView spacingValue;
    private final TextView sizeValue;
    private final TextView radiusValue;

    public DesignModuleView(View root, DesignStore store) {
        this.root = root;
        this.store = store;
        appColor = root.findViewById(R.id.design_app_color);
        sidebarColor = root.findViewById(R.id.design_sidebar_color);
        headerColor = root.findViewById(R.id.design_header_color);
        surfaceColor = root.findViewById(R.id.design_surface_color);
        accentColor = root.findViewById(R.id.design_accent_color);
        primaryTextColor = root.findViewById(R.id.design_primary_text_color);
        secondaryTextColor = root.findViewById(R.id.design_secondary_text_color);
        spacing = root.findViewById(R.id.design_spacing);
        size = root.findViewById(R.id.design_size);
        radius = root.findViewById(R.id.design_radius);
        spacingValue = root.findViewById(R.id.design_spacing_value);
        sizeValue = root.findViewById(R.id.design_size_value);
        radiusValue = root.findViewById(R.id.design_radius_value);

        bindColor(appColor, "appColor", R.color.app_background);
        bindColor(sidebarColor, "sidebarColor", R.color.sidebar_background);
        bindColor(headerColor, "headerColor", R.color.header_background);
        bindColor(surfaceColor, "surfaceColor", R.color.panel_background);
        bindColor(accentColor, "accentColor", R.color.neon_red);
        bindColor(primaryTextColor, "primaryTextColor", R.color.text_primary);
        bindColor(secondaryTextColor, "secondaryTextColor", R.color.text_secondary);

        spacing.setMax(100);
        size.setMax(100);
        radius.setMax(32);
        spacing.setProgress(Math.round((store.getFloat("spacingScale", 1f) - 0.5f) * 100f));
        size.setProgress(Math.round((store.getFloat("sizeScale", 1f) - 0.8f) * 250f));
        radius.setProgress(Math.round(store.getFloat("cornerRadiusDp", 8f)));
        updateLabels();

        spacing.setOnSeekBarChangeListener(listener(progress -> {
            store.setFloat("spacingScale", 0.5f + progress / 100f);
            updateLabels();
        }));
        size.setOnSeekBarChangeListener(listener(progress -> {
            store.setFloat("sizeScale", 0.8f + progress / 250f);
            updateLabels();
        }));
        radius.setOnSeekBarChangeListener(listener(progress -> {
            store.setFloat("cornerRadiusDp", progress);
            updateLabels();
        }));

        root.findViewById(R.id.design_reset_all).setOnClickListener(view -> reset());
    }

    private void bindColor(EditText field, String key, int resource) {
        int fallback = root.getContext().getColor(resource);
        field.setText(colorText(store.getColor(key, fallback)));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String value = s.toString().trim();
                if (value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) {
                    try {
                        store.setColor(key, Color.parseColor(value));
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private SeekBar.OnSeekBarChangeListener listener(ProgressCallback callback) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) callback.onProgress(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        };
    }

    private void updateLabels() {
        spacingValue.setText(String.format(java.util.Locale.US, "%.2fx", 0.5f + spacing.getProgress() / 100f));
        sizeValue.setText(String.format(java.util.Locale.US, "%.2fx", 0.8f + size.getProgress() / 250f));
        radiusValue.setText(radius.getProgress() + " dp");
    }

    private void reset() {
        store.reset();
        appColor.setText(colorText(root.getContext().getColor(R.color.app_background)));
        sidebarColor.setText(colorText(root.getContext().getColor(R.color.sidebar_background)));
        headerColor.setText(colorText(root.getContext().getColor(R.color.header_background)));
        surfaceColor.setText(colorText(root.getContext().getColor(R.color.panel_background)));
        accentColor.setText(colorText(root.getContext().getColor(R.color.neon_red)));
        primaryTextColor.setText(colorText(root.getContext().getColor(R.color.text_primary)));
        secondaryTextColor.setText(colorText(root.getContext().getColor(R.color.text_secondary)));
        spacing.setProgress(50);
        size.setProgress(50);
        radius.setProgress(8);
        updateLabels();
    }

    private static String colorText(int color) {
        return String.format(java.util.Locale.US, "#%08X", color);
    }

    private interface ProgressCallback {
        void onProgress(int progress);
    }
}