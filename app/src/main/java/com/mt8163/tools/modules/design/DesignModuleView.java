package com.mt8163.tools.modules.design;

import android.app.Activity;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;
import com.mt8163.tools.core.ModuleRegistry;
import com.mt8163.tools.core.design.DesignRuntime;
import com.mt8163.tools.core.design.DesignStore;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DesignModuleView {
    private final Activity activity;
    private final ModuleRegistry registry;
    private final DesignStore store;
    private final Spinner moduleSpinner;
    private final Spinner viewSpinner;
    private final FrameLayout preview;
    private final EditText backgroundColor;
    private final EditText textColor;
    private final EditText imageTintColor;
    private final EditText textSize;
    private final EditText width;
    private final EditText height;
    private final EditText padding;
    private final EditText margin;
    private final EditText radius;
    private final EditText alpha;
    private final EditText rotation;
    private final EditText elevation;
    private final List<ModuleContract> targetModules = new ArrayList<>();
    private final List<ViewEntry> targetViews = new ArrayList<>();
    private String selectedViewKey;

    public DesignModuleView(View root, Activity activity,
                            ModuleRegistry registry, DesignStore store) {
        this.activity = activity;
        this.registry = registry;
        this.store = store;
        moduleSpinner = root.findViewById(R.id.design_target_module);
        viewSpinner = root.findViewById(R.id.design_target_view);
        preview = root.findViewById(R.id.design_preview);
        backgroundColor = root.findViewById(R.id.design_background_color);
        textColor = root.findViewById(R.id.design_text_color);
        imageTintColor = root.findViewById(R.id.design_image_tint);
        textSize = root.findViewById(R.id.design_text_size);
        width = root.findViewById(R.id.design_width);
        height = root.findViewById(R.id.design_height);
        padding = root.findViewById(R.id.design_padding);
        margin = root.findViewById(R.id.design_margin);
        radius = root.findViewById(R.id.design_radius);
        alpha = root.findViewById(R.id.design_alpha);
        rotation = root.findViewById(R.id.design_rotation);
        elevation = root.findViewById(R.id.design_elevation);

        for (ModuleContract module : registry.getModules()) {
            if (!"design".equals(module.getId()) && module.getLayoutRes() != 0) {
                targetModules.add(module);
            }
        }

        List<String> moduleNames = new ArrayList<>();
        for (ModuleContract module : targetModules) {
            moduleNames.add(activity.getString(module.getNameRes()));
        }
        moduleSpinner.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, moduleNames));
        moduleSpinner.setOnItemSelectedListener(new SelectionListener(
                position -> loadModule(position)));
        viewSpinner.setOnItemSelectedListener(new SelectionListener(
                this::selectView));

        root.findViewById(R.id.design_apply).setOnClickListener(view -> applyChanges());
        root.findViewById(R.id.design_reset).setOnClickListener(view -> resetSelected());
        root.findViewById(R.id.design_reset_all).setOnClickListener(view -> {
            store.resetAll();
            loadSelectedStyle();
        });

        if (!targetModules.isEmpty()) loadModule(0);
    }

    private void loadModule(int position) {
        if (position < 0 || position >= targetModules.size()) return;
        ModuleContract module = targetModules.get(position);
        preview.removeAllViews();
        View target = LayoutInflater.from(activity)
                .inflate(module.getLayoutRes(), preview, false);
        preview.addView(target, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        DesignRuntime.attach(target, store, module.getId());

        targetViews.clear();
        collectViews(target);
        List<String> labels = new ArrayList<>();
        for (ViewEntry entry : targetViews) labels.add(entry.label);
        viewSpinner.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        if (!targetViews.isEmpty()) selectView(0);
    }

    private void collectViews(View view) {
        String key = DesignRuntime.getViewKey(view);
        if (key != null) {
            String label = key.startsWith("id:")
                    ? key.substring(3) : key.substring(key.lastIndexOf(':') + 1);
            targetViews.add(new ViewEntry(key,
                    label + " · " + view.getClass().getSimpleName()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectViews(group.getChildAt(i));
            }
        }
    }

    private void selectView(int position) {
        if (position < 0 || position >= targetViews.size()) return;
        selectedViewKey = targetViews.get(position).key;
        loadSelectedStyle();
    }

    private void loadSelectedStyle() {
        if (selectedViewKey == null) return;
        JSONObject style = store.getStyle(selectedViewKey);
        backgroundColor.setText(colorText(style, "backgroundColor"));
        textColor.setText(colorText(style, "textColor"));
        imageTintColor.setText(colorText(style, "imageTintColor"));
        textSize.setText(numberText(style, "textSizeSp"));
        width.setText(numberText(style, "widthDp"));
        height.setText(numberText(style, "heightDp"));
        padding.setText(numberText(style, "paddingDp"));
        margin.setText(numberText(style, "marginDp"));
        radius.setText(numberText(style, "cornerRadiusDp"));
        alpha.setText(numberText(style, "alpha"));
        rotation.setText(numberText(style, "rotation"));
        elevation.setText(numberText(style, "elevationDp"));
        DesignRuntime.applyTree(preview, store);
    }

    private void applyChanges() {
        if (selectedViewKey == null) return;
        JSONObject values = new JSONObject();
        try {
            putColor(values, "backgroundColor", backgroundColor);
            putColor(values, "textColor", textColor);
            putColor(values, "imageTintColor", imageTintColor);
            putNumber(values, "textSizeSp", textSize, 1, 200, true);
            putNumber(values, "widthDp", width, -2, 2000, false);
            putNumber(values, "heightDp", height, -2, 2000, false);
            putNumber(values, "paddingDp", padding, 0, 500, false);
            putNumber(values, "marginDp", margin, 0, 500, false);
            putNumber(values, "cornerRadiusDp", radius, 0, 500, false);
            putNumber(values, "alpha", alpha, 0, 1, true);
            putNumber(values, "rotation", rotation, -360, 360, true);
            putNumber(values, "elevationDp", elevation, 0, 100, false);
        } catch (IllegalArgumentException | JSONException error) {
            Toast.makeText(activity, R.string.design_invalid_value,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        store.updateStyle(selectedViewKey, values);
        Toast.makeText(activity, R.string.design_applied, Toast.LENGTH_SHORT).show();
    }

    private void resetSelected() {
        if (selectedViewKey == null) return;
        store.resetView(selectedViewKey);
        loadSelectedStyle();
    }

    private static void putColor(JSONObject values, String key, EditText field)
            throws JSONException {
        String value = field.getText().toString().trim();
        if (!value.isEmpty()) values.put(key, Color.parseColor(value));
    }

    private static void putNumber(JSONObject values, String key, EditText field,
                                  float minimum, float maximum, boolean decimal)
            throws JSONException {
        String value = field.getText().toString().trim();
        if (value.isEmpty()) return;
        float parsed = Float.parseFloat(value);
        if (parsed < minimum || parsed > maximum
                || (!decimal && parsed != Math.round(parsed))) {
            throw new IllegalArgumentException();
        }
        if (decimal) values.put(key, parsed);
        else values.put(key, (int) parsed);
    }

    private static String colorText(JSONObject style, String key) {
        if (!style.has(key)) return "";
        return String.format(Locale.US, "#%08X", style.optInt(key));
    }

    private static String numberText(JSONObject style, String key) {
        if (!style.has(key)) return "";
        Object value = style.opt(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static final class ViewEntry {
        final String key;
        final String label;

        ViewEntry(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private interface SelectionCallback {
        void onSelected(int position);
    }

    private static final class SelectionListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        private final SelectionCallback callback;

        SelectionListener(SelectionCallback callback) {
            this.callback = callback;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent,
                                   View view, int position, long id) {
            callback.onSelected(position);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
