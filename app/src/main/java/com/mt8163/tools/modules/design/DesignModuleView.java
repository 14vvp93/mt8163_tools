package com.mt8163.tools.modules.design;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.mt8163.tools.R;
import com.mt8163.tools.core.design.DesignStore;

import java.util.Locale;

public final class DesignModuleView {
    private final View root;
    private final DesignStore store;
    private Dialog dialog;
    private LinearLayout controls;
    private LinearLayout dialogContent;
    private boolean minimized;

    private static final ColorSetting[] COLORS = {
        new ColorSetting("App background", "appColor", R.color.app_background),
        new ColorSetting("Sidebar", "sidebarColor", R.color.sidebar_background),
        new ColorSetting("Header", "headerColor", R.color.header_background),
        new ColorSetting("Panel surface", "surfaceColor", R.color.panel_background),
        new ColorSetting("Dialog surface", "dialogColor", R.color.dialog_background),
        new ColorSetting("Accent", "accentColor", R.color.neon_red),
        new ColorSetting("Primary text", "primaryTextColor", R.color.text_primary),
        new ColorSetting("Secondary text", "secondaryTextColor", R.color.text_secondary),
        new ColorSetting("Muted text", "mutedTextColor", R.color.text_muted),
        new ColorSetting("Divider", "lineColor", R.color.line),
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
        new ColorSetting("Switch disabled", "switchDisabledColor", R.color.switch_track_disabled),
        new ColorSetting("Accent overlay", "accentOverlayColor", R.color.accent_overlay),
        new ColorSetting("Accent stroke", "accentStrokeColor", R.color.accent_stroke)
    };

    private static final NumberSetting[] NUMBERS = {
        new NumberSetting("Spacing scale", "spacingScale", .50f, 1.50f, 100, 1f, "x"),
        new NumberSetting("Element scale", "sizeScale", .60f, 1.40f, 80, 1f, "x"),
        new NumberSetting("Text scale", "textScale", .70f, 1.50f, 80, 1f, "x"),
        new NumberSetting("Icon scale", "iconScale", .60f, 1.60f, 100, 1f, "x"),
        new NumberSetting("Corner radius", "cornerRadiusDp", 0f, 24f, 48, 8f, " dp"),
        new NumberSetting("Border width", "borderWidthDp", 0f, 4f, 40, 1f, " dp"),
        new NumberSetting("Sidebar width", "sidebarWidthDp", 180f, 340f, 160, 260f, " dp"),
        new NumberSetting("Header height", "headerHeightDp", 56f, 112f, 56, 80f, " dp"),
        new NumberSetting("Page padding", "pagePaddingDp", 4f, 40f, 72, 24f, " dp"),
        new NumberSetting("Card padding", "cardPaddingDp", 4f, 32f, 56, 16f, " dp"),
        new NumberSetting("Button height", "buttonHeightDp", 32f, 64f, 64, 48f, " dp"),
        new NumberSetting("Button minimum width", "buttonMinWidthDp", 80f, 180f, 100, 120f, " dp"),
        new NumberSetting("Icon size", "iconSizeDp", 16f, 48f, 64, 24f, " dp"),
        new NumberSetting("Switch minimum width", "switchMinWidthDp", 48f, 100f, 52, 72f, " dp"),
        new NumberSetting("Brand header height", "brandHeaderHeightDp", 48f, 100f, 52, 72f, " dp"),
        new NumberSetting("Brand mark size", "brandMarkSizeDp", 28f, 56f, 56, 44f, " dp"),
        new NumberSetting("Sidebar item height", "sidebarItemHeightDp", 44f, 80f, 72, 60f, " dp"),
        new NumberSetting("Sidebar item padding", "sidebarItemPaddingDp", 4f, 24f, 40, 12f, " dp"),
        new NumberSetting("Sidebar icon padding", "sidebarIconPaddingDp", 2f, 16f, 28, 8f, " dp"),
        new NumberSetting("Status indicator size", "statusDotSizeDp", 4f, 16f, 24, 8f, " dp"),
        new NumberSetting("Switch track minimum width", "switchTrackMinWidthDp", 40f, 80f, 40, 56f, " dp"),
        new NumberSetting("Brand mark corner radius", "brandMarkRadiusDp", 0f, 20f, 40, 6f, " dp"),
        new NumberSetting("Page title", "textPageTitleSp", 18f, 32f, 56, 24f, " sp"),
        new NumberSetting("Section title", "textSectionTitleSp", 12f, 22f, 50, 16f, " sp"),
        new NumberSetting("Body text", "textBodySp", 10f, 20f, 50, 14f, " sp"),
        new NumberSetting("Secondary text", "textSecondarySp", 9f, 18f, 45, 12f, " sp"),
        new NumberSetting("Label text", "textLabelSp", 10f, 18f, 40, 13f, " sp"),
        new NumberSetting("Button text", "textButtonSp", 11f, 21f, 50, 15f, " sp"),
        new NumberSetting("Large value", "textLargeValueSp", 28f, 56f, 56, 44f, " sp"),
        new NumberSetting("Icon button text", "textIconButtonSp", 18f, 36f, 54, 28f, " sp"),
        new NumberSetting("Extra small spacing", "spacingXsDp", 2f, 8f, 60, 4f, " dp"),
        new NumberSetting("Small spacing", "spacingSmDp", 4f, 12f, 80, 8f, " dp"),
        new NumberSetting("Medium spacing", "spacingMdDp", 6f, 18f, 120, 12f, " dp"),
        new NumberSetting("Large spacing", "spacingLgDp", 8f, 24f, 160, 16f, " dp"),
        new NumberSetting("Extra large spacing", "spacingXlDp", 10f, 30f, 200, 20f, " dp"),
        new NumberSetting("XXL spacing", "spacingXxlDp", 12f, 36f, 240, 24f, " dp"),
        new NumberSetting("Small icon size", "iconSizeSmallDp", 12f, 32f, 80, 20f, " dp"),
        new NumberSetting("Sidebar icon size", "sidebarIconSizeDp", 24f, 48f, 96, 36f, " dp"),
        new NumberSetting("Sidebar divider height", "sidebarDividerHeightDp", 1f, 12f, 44, 6f, " dp"),
        new NumberSetting("Emphasis border width", "borderWidthEmphasisDp", 1f, 4f, 30, 2f, " dp"),
        new NumberSetting("SeekBar height", "seekbarHeightDp", 16f, 40f, 48, 24f, " dp"),
        new NumberSetting("Large control size", "controlSizeLargeDp", 28f, 56f, 56, 40f, " dp"),
        new NumberSetting("Protection card minimum height", "protectionCardMinHeightDp", 48f, 96f, 96, 64f, " dp"),
        new NumberSetting("Protection separator height", "protectionSeparatorHeightDp", 12f, 44f, 64, 28f, " dp"),
        new NumberSetting("Picker dialog maximum width", "pickerDialogMaxWidthDp", 420f, 720f, 120, 640f, " dp"),
        new NumberSetting("Picker dialog maximum height", "pickerDialogMaxHeightDp", 300f, 560f, 104, 430f, " dp"),
        new NumberSetting("Protection settings dialog width", "protectionSettingsDialogWidthDp", 360f, 600f, 96, 520f, " dp"),
        new NumberSetting("Protection settings dialog height", "protectionSettingsDialogHeightDp", 180f, 340f, 80, 260f, " dp"),
        new NumberSetting("Protection confirmation height", "protectionConfirmDialogHeightDp", 140f, 280f, 70, 200f, " dp"),
        new NumberSetting("Events filter dialog width", "eventsFilterDialogWidthDp", 320f, 560f, 96, 480f, " dp"),
        new NumberSetting("Events filter dialog height", "eventsFilterDialogHeightDp", 360f, 640f, 112, 560f, " dp"),
        new NumberSetting("Events confirmation width", "eventsConfirmDialogWidthDp", 320f, 560f, 96, 480f, " dp"),
        new NumberSetting("Events confirmation height", "eventsConfirmDialogHeightDp", 160f, 300f, 70, 220f, " dp"),
        new NumberSetting("Events export dialog height", "eventsExportDialogHeightDp", 220f, 400f, 90, 300f, " dp")
    };

    public DesignModuleView(View root, DesignStore store) {
        this.root = root;
        this.store = store;
        Button open = new Button(root.getContext());
        open.setText("Open Design Lab");
        open.setOnClickListener(view -> show());
        LinearLayout host = root.findViewById(R.id.design_controls);
        host.removeAllViews();
        host.addView(open, new LinearLayout.LayoutParams(-1, dp(48)));
        root.findViewById(R.id.design_reset_all).setVisibility(View.GONE);
        open.performClick();
    }

    private void show() {
        if (dialog != null && dialog.isShowing()) return;
        Context context = root.getContext();
        dialog = new Dialog(context);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(shape(context.getColor(R.color.dialog_background),
                context.getColor(R.color.line), dp(1), dp(10)));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(8), dp(10), dp(8));
        header.setBackgroundColor(context.getColor(R.color.header_background));
        TextView title = label("DESIGN LAB");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button minimize = new Button(context);
        minimize.setText("—");
        minimize.setTextSize(18);
        minimize.setMinWidth(dp(44));
        minimize.setOnClickListener(v -> {
            minimized = !minimized;
            dialogContent.setVisibility(minimized ? View.GONE : View.VISIBLE);
            minimize.setText(minimized ? "□" : "—");
            Window current = dialog.getWindow();
            if (current != null) current.setLayout(
                    Math.min(dp(900), context.getResources().getDisplayMetrics().widthPixels - dp(64)),
                    minimized ? dp(58) : Math.min(dp(500),
                            context.getResources().getDisplayMetrics().heightPixels - dp(64)));
        });
        header.addView(minimize, new LinearLayout.LayoutParams(dp(44), dp(42)));
        Button close = new Button(context);
        close.setText("×");
        close.setTextSize(22);
        close.setMinWidth(dp(44));
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(42)));
        panel.addView(header);
        makeDraggable(header);

        dialogContent = new LinearLayout(context);
        dialogContent.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(18), dp(8), dp(18), dp(12));
        scroll.addView(controls);
        dialogContent.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        buildColors();
        buildNumbers();

        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(12), dp(6), dp(12), dp(6));
        Button reset = new Button(context);
        reset.setText("Reset");
        reset.setOnClickListener(v -> {
            store.reset();
            dialog.dismiss();
            show();
        });
        Button done = new Button(context);
        done.setText("Done");
        done.setOnClickListener(v -> dialog.dismiss());
        footer.addView(reset);
        footer.addView(done);
        dialogContent.addView(footer);
        panel.addView(dialogContent, new LinearLayout.LayoutParams(-1, 0, 1f));
        minimized = false;

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(.35f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        if (window != null) {
            int width = Math.min(dp(900), context.getResources().getDisplayMetrics().widthPixels - dp(64));
            int height = Math.min(dp(500), context.getResources().getDisplayMetrics().heightPixels - dp(64));
            window.setLayout(width, height);
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }
    }

    private void makeDraggable(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY;
            int startX, startY;
            boolean dragging;

            @Override public boolean onTouch(View view, MotionEvent event) {
                Window window = dialog.getWindow();
                if (window == null) return false;
                WindowManager.LayoutParams params = window.getAttributes();
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    int[] location = new int[2];
                    window.getDecorView().getLocationOnScreen(location);
                    startX = location[0];
                    startY = location[1];
                    dragging = false;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(4)) dragging = true;
                    if (dragging) {
                        int screenWidth = root.getResources().getDisplayMetrics().widthPixels;
                        int screenHeight = root.getResources().getDisplayMetrics().heightPixels;
                        int windowWidth = window.getDecorView().getWidth();
                        int windowHeight = window.getDecorView().getHeight();
                        int visibleHandle = dp(72);
                        int maxX = Math.max(0, screenWidth - visibleHandle);
                        int minX = Math.min(0, visibleHandle - windowWidth);
                        int maxY = Math.max(0, screenHeight - dp(48));
                        params.gravity = Gravity.TOP | Gravity.LEFT;
                        params.x = Math.max(minX, Math.min(maxX, startX + Math.round(dx)));
                        params.y = Math.max(0, Math.min(maxY, startY + Math.round(dy)));
                        window.setAttributes(params);
                    }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (!dragging && event.getX() > view.getWidth() - dp(60)) dialog.dismiss();
                    return true;
                }
                return true;
            }
        });
    }

    private void buildColors() {
        section("COLORS");
        for (ColorSetting setting : COLORS) {
            int fallback = root.getContext().getColor(setting.resource);
            int color = store.getColor(setting.key, fallback);
            LinearLayout row = new LinearLayout(root.getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView name = label(setting.label);
            row.addView(name, new LinearLayout.LayoutParams(0, dp(42), 1f));
            Button swatch = new Button(root.getContext());
            swatch.setText(colorText(color));
            swatch.setTextSize(11);
            swatch.setTextColor(contrast(color));
            swatch.setBackground(shape(color, root.getContext().getColor(R.color.line), dp(1), dp(6)));
            swatch.setOnClickListener(v -> showColorPicker(setting, fallback));
            row.addView(swatch, new LinearLayout.LayoutParams(dp(150), dp(40)));
            controls.addView(row);
        }
    }

    private void showColorPicker(ColorSetting setting, int fallback) {
        Context context = root.getContext();
        int initial = store.getColor(setting.key, fallback);
        Dialog picker = new Dialog(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(14), dp(18), dp(14));
        body.setBackground(shape(context.getColor(R.color.dialog_background),
                context.getColor(R.color.line), dp(1), dp(10)));
        TextView heading = label(setting.label);
        heading.setTextSize(17);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        body.addView(heading);
        View preview = new View(context);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(48));
        previewParams.topMargin = dp(10);
        body.addView(preview, previewParams);
        EditText hex = new EditText(context);
        hex.setSingleLine(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setHint("#AARRGGBB");
        hex.setText(colorText(initial));
        body.addView(hex, new LinearLayout.LayoutParams(-1, dp(44)));

        float[] hsv = new float[3];
        Color.colorToHSV(initial, hsv);
        SeekBar hue = slider(360, Math.round(hsv[0]));
        SeekBar saturation = slider(100, Math.round(hsv[1] * 100));
        SeekBar value = slider(100, Math.round(hsv[2] * 100));
        SeekBar alpha = slider(255, Color.alpha(initial));
        addSlider(body, "Hue", hue);
        addSlider(body, "Saturation", saturation);
        addSlider(body, "Brightness", value);
        addSlider(body, "Opacity", alpha);

        final int[] selected = {initial};
        final boolean[] updating = {false};
        Runnable updateFromSliders = () -> {
            if (updating[0]) return;
            float[] nextHsv = {hue.getProgress(), saturation.getProgress() / 100f,
                    value.getProgress() / 100f};
            selected[0] = Color.HSVToColor(alpha.getProgress(), nextHsv);
            preview.setBackgroundColor(selected[0]);
            updating[0] = true;
            hex.setText(colorText(selected[0]));
            hex.setSelection(hex.length());
            updating[0] = false;
        };
        SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) updateFromSliders.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        };
        hue.setOnSeekBarChangeListener(sliderListener);
        saturation.setOnSeekBarChangeListener(sliderListener);
        value.setOnSeekBarChangeListener(sliderListener);
        alpha.setOnSeekBarChangeListener(sliderListener);
        preview.setBackgroundColor(initial);

        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (updating[0]) return;
                String text = s.toString().trim();
                if (!text.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) return;
                try {
                    int parsed = Color.parseColor(text);
                    selected[0] = parsed;
                    preview.setBackgroundColor(parsed);
                    float[] hsvValue = new float[3];
                    Color.colorToHSV(parsed, hsvValue);
                    updating[0] = true;
                    hue.setProgress(Math.round(hsvValue[0]));
                    saturation.setProgress(Math.round(hsvValue[1] * 100));
                    value.setProgress(Math.round(hsvValue[2] * 100));
                    alpha.setProgress(Color.alpha(parsed));
                    updating[0] = false;
                } catch (IllegalArgumentException ignored) { }
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        LinearLayout palette = new LinearLayout(context);
        palette.setOrientation(LinearLayout.HORIZONTAL);
        palette.setGravity(Gravity.CENTER);
        int[] colors = {0xFFE53935,0xFFFF5722,0xFFFF9800,0xFFFFC107,0xFF4CAF50,
                0xFF009688,0xFF03A9F4,0xFF2196F3,0xFF3F51B5,0xFF673AB7,
                0xFF9C27B0,0xFFE91E63,0xFFFFFFFF,0xFFB0BEC5,0xFF616161,0xFF17191C};
        for (int preset : colors) {
            View chip = new View(context);
            chip.setBackground(shape(preset, context.getColor(R.color.line), dp(1), dp(20)));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, dp(26), 1f);
            chipParams.setMargins(dp(2), dp(8), dp(2), dp(8));
            palette.addView(chip, chipParams);
            chip.setOnClickListener(v -> {
                selected[0] = preset;
                float[] hsvPreset = new float[3];
                Color.colorToHSV(preset, hsvPreset);
                updating[0] = true;
                hue.setProgress(Math.round(hsvPreset[0]));
                saturation.setProgress(Math.round(hsvPreset[1] * 100));
                value.setProgress(Math.round(hsvPreset[2] * 100));
                alpha.setProgress(255);
                hex.setText(colorText(preset));
                hex.setSelection(hex.length());
                updating[0] = false;
                preview.setBackgroundColor(preset);
            });
        }
        body.addView(palette);
        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.RIGHT);
        Button cancel = new Button(context);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> picker.dismiss());
        Button apply = new Button(context);
        apply.setText("Apply");
        apply.setOnClickListener(v -> {
            store.setColor(setting.key, selected[0]);
            picker.dismiss();
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
                show();
            }
        });
        actions.addView(cancel);
        actions.addView(apply);
        body.addView(actions);
        picker.setContentView(body);
        picker.show();
        Window window = picker.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(Math.min(dp(520), context.getResources().getDisplayMetrics().widthPixels - dp(40)),
                    -2);
        }
    }

    private void buildNumbers() {
        section("LAYOUT AND TYPOGRAPHY");
        for (NumberSetting setting : NUMBERS) {
            float currentValue = store.getFloat(setting.key, setting.defaultValue);
            LinearLayout row = new LinearLayout(root.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));
            LinearLayout heading = new LinearLayout(root.getContext());
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label(setting.label);
            TextView value = label(format(setting, currentValue));
            heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            heading.addView(value);
            SeekBar seek = new SeekBar(root.getContext());
            seek.setMax(setting.steps);
            seek.setProgress(progress(setting, currentValue));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int position, boolean fromUser) {
                    if (!fromUser) return;
                    float next = setting.min + (setting.max - setting.min) * position / setting.steps;
                    next = Math.round(next * 100f) / 100f;
                    store.setFloat(setting.key, next);
                    value.setText(format(setting, next));
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            row.addView(heading);
            row.addView(seek, new LinearLayout.LayoutParams(-1, dp(30)));
            controls.addView(row);
        }
    }

    private void section(String text) {
        TextView title = label(text);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(root.getContext().getColor(R.color.neon_red));
        title.setPadding(0, dp(16), 0, dp(8));
        controls.addView(title);
    }

    private TextView label(String text) {
        TextView view = new TextView(root.getContext());
        view.setText(text);
        view.setTextColor(root.getContext().getColor(R.color.text_primary));
        view.setTextSize(13);
        return view;
    }

    private SeekBar slider(int max, int progress) {
        SeekBar bar = new SeekBar(root.getContext());
        bar.setMax(max);
        bar.setProgress(progress);
        return bar;
    }

    private void addSlider(LinearLayout parent, String title, SeekBar slider) {
        TextView label = label(title);
        parent.addView(label);
        parent.addView(slider, new LinearLayout.LayoutParams(-1, dp(30)));
    }

    private GradientDrawable shape(int fill, int stroke, int width, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(width, stroke);
        return drawable;
    }

    private int contrast(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 > 150
                ? Color.BLACK : Color.WHITE;
    }

    private int progress(NumberSetting setting, float value) {
        return Math.max(0, Math.min(setting.steps,
                Math.round((value - setting.min) * setting.steps / (setting.max - setting.min))));
    }

    private String format(NumberSetting setting, float value) {
        return setting.unit.equals("x") ? String.format(Locale.US, "%.2fx", value)
                : String.format(Locale.US, "%.0f%s", value, setting.unit);
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
            this.label = label;
            this.key = key;
            this.resource = resource;
        }
    }

    private static final class NumberSetting {
        final String label, key, unit;
        final float min, max, defaultValue;
        final int steps;
        NumberSetting(String label, String key, float min, float max, int steps,
                      float defaultValue, String unit) {
            this.label = label;
            this.key = key;
            this.min = min;
            this.max = max;
            this.steps = steps;
            this.defaultValue = defaultValue;
            this.unit = unit;
        }
    }
}
