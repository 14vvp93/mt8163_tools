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
    private final EditText textSize;
    private final EditText width;
    private final EditText height;
    private final EditText padding;
    private final EditText margin;
    private final EditText radius;
    private final EditText alpha;
    private final List<ModuleContract> targetModules = new ArrayList<>();
    private final List<ViewEntry> targetViews = new ArrayList<>();
    private String selectedViewName;

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
        textSize = root.findViewById(R.id.design_text_size);
        width = root.findViewById(R.id.design_width);
        height = root.findViewById(R.id.design_height);
        padding = root.findViewById(R.id.design_padding);
        margin = root.findViewById(R.id.design_margin);
        radius = root.findViewById(R.id.design_radius);
        alpha = root.findViewById(R.id.design_alpha);

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
                position -> selectView(position)));

        Button apply = root.findViewById(R.id.design_apply);
        Button reset = root.findViewById(R.id.design_reset);
        Button resetAll = root.findViewById(R.id.design_reset_all);
        apply.setOnClickListener(view -> applyChanges());
        reset.setOnClickListener(view -> resetSelected());
        resetAll.setOnClickListener(view -> {
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
        DesignRuntime.attach(target, store);

        targetViews.clear();
        collectViews(target);
        List<String> labels = new ArrayList<>();
        for (ViewEntry entry : targetViews) labels.add(entry.label);
        viewSpinner.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        if (!targetViews.isEmpty()) selectView(0);
    }

    private void collectViews(View view) {
        if (view.getId() != View.NO_ID) {
            try {
                String name = view.getResources().getResourceEntryName(view.getId());
                targetViews.add(new ViewEntry(view, name,
                        name + " · " + view.getClass().getSimpleName()));
            } catch (RuntimeException ignored) {
            }
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
        selectedViewName = targetViews.get(position).name;
        loadSelectedStyle();
    }

    private void loadSelectedStyle() {
        if (selectedViewName == null) return;
        JSONObject style = store.getStyle(selectedViewName);
        backgroundColor.setText(colorText(style, "backgroundColor"));
        textColor.setText(colorText(style, "textColor"));
        textSize.setText(numberText(style, "textSizeSp"));
        width.setText(numberText(style, "widthDp"));
        height.setText(numberText(style, "heightDp"));
        padding.setText(numberText(style, "paddingDp"));
        margin.setText(numberText(style, "marginDp"));
        radius.setText(numberText(style, "cornerRadiusDp"));
        alpha.setText(numberText(style, "alpha"));
        DesignRuntime.applyTree(preview, store);
    }

    private void applyChanges() {
        if (selectedViewName == null) return;
        JSONObject values = new JSONObject();
        try {
            putColor(values, "backgroundColor", backgroundColor);
            putColor(values, "textColor", textColor);
            putNumber(values, "textSizeSp", textSize, true);
            putNumber(values, "widthDp", width, false);
            putNumber(values, "heightDp", height, false);
            putNumber(values, "paddingDp", padding, false);
            putNumber(values, "marginDp", margin, false);
            putNumber(values, "cornerRadiusDp", radius, false);
            putNumber(values, "alpha", alpha, true);
        } catch (IllegalArgumentException | JSONException error) {
            Toast.makeText(activity, R.string.design_invalid_value,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        store.updateStyle(selectedViewName, values);
        Toast.makeText(activity, R.string.design_applied, Toast.LENGTH_SHORT).show();
    }

    private void resetSelected() {
        if (selectedViewName == null) return;
        store.resetView(selectedViewName);
        loadSelectedStyle();
    }

    private static void putColor(JSONObject values, String key, EditText field)
            throws JSONException {
        String value = field.getText().toString().trim();
        if (value.isEmpty()) return;
        values.put(key, Color.parseColor(value));
    }

    private static void putNumber(JSONObject values, String key, EditText field,
                                  boolean decimal) throws JSONException {
        String value = field.getText().toString().trim();
        if (value.isEmpty()) return;
        if ("alpha".equals(key)) {
            float parsed = Float.parseFloat(value);
            if (parsed < 0f || parsed > 1f) throw new IllegalArgumentException();
            values.put(key, parsed);
        } else if (decimal) {
            float parsed = Float.parseFloat(value);
            if (parsed <= 0f || parsed > 200f) throw new IllegalArgumentException();
            values.put(key, parsed);
        } else {
            int parsed = Integer.parseInt(value);
            if (parsed < -2 || parsed > 2000) throw new IllegalArgumentException();
            values.put(key, parsed);
        }
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
        final View view;
        final String name;
        final String label;

        ViewEntry(View view, String name, String label) {
            this.view = view;
            this.name = name;
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
