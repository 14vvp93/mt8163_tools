package com.mt8163.tools.core.design;

import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.mt8163.tools.R;

public final class DesignRuntime {
    private DesignRuntime() { }

    public static void attach(View root, DesignStore store, String scope) {
        if (root == null || store == null) return;
        applyTree(root, store);
        attachHierarchy(root, store);
    }

    public static void applyTree(View root, DesignStore store) {
        if (root == null || store == null) return;
        applyView(root, store);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) applyTree(group.getChildAt(i), store);
        }
    }

    private static void attachHierarchy(View view, DesignStore store) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        if (!Boolean.TRUE.equals(group.getTag(R.id.design_listener_scope))) {
            group.setTag(R.id.design_listener_scope, Boolean.TRUE);
            group.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) {
                    applyTree(child, store);
                    attachHierarchy(child, store);
                }
                @Override public void onChildViewRemoved(View parent, View child) { }
            });
        }
        for (int i = 0; i < group.getChildCount(); i++) attachHierarchy(group.getChildAt(i), store);
    }

    private static void applyView(View view, DesignStore store) {
        applyColors(view, store);
        applyDimensions(view, store);
    }

    private static void applyColors(View view, DesignStore store) {
        int[] base = {
            color(view, R.color.app_background), color(view, R.color.sidebar_background),
            color(view, R.color.header_background), color(view, R.color.panel_background),
            color(view, R.color.dialog_background), color(view, R.color.neon_red),
            color(view, R.color.text_primary), color(view, R.color.text_secondary),
            color(view, R.color.text_muted), color(view, R.color.line),
            color(view, R.color.button_background), color(view, R.color.button_pressed_background),
            color(view, R.color.button_stroke), color(view, R.color.button_disabled),
            color(view, R.color.sidebar_icon_background), color(view, R.color.module_status_active),
            color(view, R.color.module_status_inactive), color(view, R.color.switch_thumb),
            color(view, R.color.switch_track_default), color(view, R.color.switch_track_checked),
            color(view, R.color.switch_track_disabled), color(view, R.color.accent_overlay),
            color(view, R.color.accent_stroke), color(view, R.color.white)
        };
        String[] keys = {
            "appColor", "sidebarColor", "headerColor", "surfaceColor", "dialogColor",
            "accentColor", "primaryTextColor", "secondaryTextColor", "mutedTextColor",
            "lineColor", "buttonColor", "buttonPressedColor", "buttonStrokeColor",
            "buttonDisabledColor", "iconSurfaceColor", "statusActiveColor",
            "statusInactiveColor", "switchThumbColor", "switchTrackColor",
            "switchCheckedColor", "switchDisabledColor", "accentOverlayColor",
            "accentStrokeColor", "seekbarThumbColor"
        };

        if (view.getId() == R.id.sidebar_item_root) {
            boolean selected = view.isActivated();
            GradientDrawable item = new GradientDrawable();
            item.setCornerRadius(dp(view, store.getFloat("cornerRadiusDp", 8f)));
            if (selected) {
                item.setColor(store.getColor("accentOverlayColor", color(view, R.color.accent_overlay)));
                item.setStroke(dp(view, store.getFloat("borderWidthDp", 1f)),
                        store.getColor("accentStrokeColor", color(view, R.color.accent_stroke)));
            } else {
                item.setColor(android.graphics.Color.TRANSPARENT);
                item.setStroke(0, android.graphics.Color.TRANSPARENT);
            }
            view.setBackground(item);
        }
        if (view instanceof SeekBar) applySeekBarColors((SeekBar) view, store);
        if (view instanceof Button && !(view instanceof Switch)) applyButtonColors((Button) view, store);
        if (view instanceof Switch) applySwitchColors((Switch) view, store);

        if (view instanceof TextView) {
            TextView text = (TextView) view;
            ColorStateList original = (ColorStateList) view.getTag(R.id.design_base_text_color);
            if (original == null) {
                original = text.getTextColors();
                view.setTag(R.id.design_base_text_color, original);
            }
            text.setTextColor(map(original.getDefaultColor(), base, keys, store));
        }

        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            ColorStateList original = (ColorStateList) view.getTag(R.id.design_base_image_tint);
            if (original == null && image.getImageTintList() != null) {
                original = image.getImageTintList();
                view.setTag(R.id.design_base_image_tint, original);
            }
            if (original != null) image.setImageTintList(ColorStateList.valueOf(
                    map(original.getDefaultColor(), base, keys, store)));
        }

        ColorStateList tint = (ColorStateList) view.getTag(R.id.design_base_background_tint);
        if (tint == null && view.getBackgroundTintList() != null) {
            tint = view.getBackgroundTintList();
            view.setTag(R.id.design_base_background_tint, tint);
        }
        if (tint != null) view.setBackgroundTintList(ColorStateList.valueOf(
                map(tint.getDefaultColor(), base, keys, store)));

        Drawable background = view.getBackground();
        if (background instanceof ColorDrawable) {
            ColorDrawable drawable = (ColorDrawable) background.mutate();
            Integer original = (Integer) view.getTag(R.id.design_base_background_color);
            if (original == null) {
                original = drawable.getColor();
                view.setTag(R.id.design_base_background_color, original);
            }
            drawable.setColor(map(original, base, keys, store));
        } else if (background instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) background.mutate();
            ColorStateList fill = shape.getColor();
            if (fill != null) {
                Integer original = (Integer) view.getTag(R.id.design_base_gradient_color);
                if (original == null) {
                    original = fill.getDefaultColor();
                    view.setTag(R.id.design_base_gradient_color, original);
                }
                shape.setColor(map(original, base, keys, store));
            }
            shape.setCornerRadius(dp(view, store.getFloat("cornerRadiusDp", 8f)));
            int border = dp(view, store.getFloat("borderWidthDp", 1f));
            int borderColor = store.getColor("lineColor", color(view, R.color.line));
            shape.setStroke(border, borderColor);
            view.setBackground(shape);
        }
    }


    private static void applySeekBarColors(SeekBar bar, DesignStore store) {
        int trackHeight = Math.max(1, dp(bar, store.getFloat("seekbarTrackHeightDp", 4f)));
        int trackColor = store.getColor("lineColor", color(bar, R.color.line));
        int progressColor = store.getColor("accentColor", color(bar, R.color.neon_red));
        int thumbColor = store.getColor("seekbarThumbColor", color(bar, R.color.white));

        GradientDrawable background = track(trackColor, trackHeight);
        GradientDrawable secondary = track(trackColor, trackHeight);
        GradientDrawable progress = track(progressColor, trackHeight);
        ClipDrawable clippedProgress = new ClipDrawable(progress, Gravity.START,
                ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(
                new Drawable[]{background, secondary, clippedProgress});

        int[] ids = {android.R.id.background, android.R.id.secondaryProgress,
                android.R.id.progress};
        for (int i = 0; i < ids.length; i++) {
            layers.setId(i, ids[i]);
            layers.setLayerWidth(i, -1);
            layers.setLayerHeight(i, trackHeight);
            layers.setLayerGravity(i, Gravity.CENTER_VERTICAL);
        }

        bar.setProgressDrawable(layers);
        bar.setThumbTintList(ColorStateList.valueOf(thumbColor));
        bar.setSplitTrack(false);
    }

    private static GradientDrawable track(int color, int height) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(height / 2f);
        return drawable;
    }

    private static void applyButtonColors(Button button, DesignStore store) {
        int normal = store.getColor("buttonColor", color(button, R.color.button_background));
        int pressed = store.getColor("buttonPressedColor", color(button, R.color.button_pressed_background));
        int disabled = store.getColor("buttonDisabledColor", color(button, R.color.button_disabled));
        int stroke = store.getColor("buttonStrokeColor", color(button, R.color.button_stroke));
        int accent = store.getColor("accentColor", color(button, R.color.neon_red));
        int border = dp(button, store.getFloat("borderWidthDp", 1f));
        float radius = dp(button, store.getFloat("cornerRadiusDp", 8f));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},
                buttonShape(disabled, stroke, border, radius));
        states.addState(new int[]{android.R.attr.state_pressed},
                buttonShape(pressed, accent, border, radius));
        states.addState(new int[]{android.R.attr.state_focused},
                buttonShape(pressed, accent, border, radius));
        states.addState(new int[0], buttonShape(normal, stroke, border, radius));
        button.setBackground(states);
    }

    private static GradientDrawable buttonShape(int fill, int stroke, int width, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radius);
        shape.setStroke(width, stroke);
        return shape;
    }

    private static void applySwitchColors(Switch toggle, DesignStore store) {
        int thumb = store.getColor("switchThumbColor", color(toggle, R.color.switch_thumb));
        int normal = store.getColor("switchTrackColor", color(toggle, R.color.switch_track_default));
        int checked = store.getColor("switchCheckedColor", color(toggle, R.color.switch_track_checked));
        int disabled = store.getColor("switchDisabledColor", color(toggle, R.color.switch_track_disabled));
        toggle.setThumbTintList(ColorStateList.valueOf(thumb));
        toggle.setTrackTintList(new ColorStateList(
                new int[][]{
                    new int[]{-android.R.attr.state_enabled},
                    new int[]{android.R.attr.state_checked},
                    new int[0]
                },
                new int[]{disabled, checked, normal}
        ));
    }

    private static int map(int color, int[] base, String[] keys, DesignStore store) {
        for (int i = 0; i < base.length; i++) {
            if (color == base[i]) return store.getColor(keys[i], base[i]);
        }
        return color;
    }

    private static int color(View view, int resource) {
        return view.getContext().getColor(resource);
    }

    private static void applyDimensions(View view, DesignStore store) {
        float spacing = store.getFloat("spacingScale", 1f);
        float elements = store.getFloat("sizeScale", 1f);
        float textScale = store.getFloat("textScale", elements);
        float iconScale = store.getFloat("iconScale", elements);

        int[] padding = (int[]) view.getTag(R.id.design_base_padding);
        if (padding == null) {
            padding = new int[]{view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), view.getPaddingBottom()};
            view.setTag(R.id.design_base_padding, padding);
        }
        view.setPadding(scaleSpacing(view, padding[0], spacing, store),
                scaleSpacing(view, padding[1], spacing, store),
                scaleSpacing(view, padding[2], spacing, store),
                scaleSpacing(view, padding[3], spacing, store));

        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            int[] dimensions = (int[]) view.getTag(R.id.design_base_dimensions);
            if (dimensions == null) {
                dimensions = new int[]{params.width, params.height};
                view.setTag(R.id.design_base_dimensions, dimensions);
            }
            float dimensionScale = view instanceof ImageView ? iconScale : elements;
            params.width = scaleLayout(dimensions[0], dimensionScale);
            params.height = scaleLayout(dimensions[1], dimensionScale);
            if (view instanceof ImageView && dimensions[0] > 0 && dimensions[1] > 0) {
                float baseMax = Math.max(dimensions[0], dimensions[1]);
                float baseDp = baseMax / view.getResources().getDisplayMetrics().density;
                float iconSize = dp(view, baseDp <= 20.5f
                        ? store.getFloat("iconSizeSmallDp", 20f)
                        : store.getFloat("iconSizeDp", 24f));
                params.width = Math.round(dimensions[0] * iconSize / baseMax * iconScale);
                params.height = Math.round(dimensions[1] * iconSize / baseMax * iconScale);
            }
            if (view instanceof SeekBar) {
                params.height = dp(view, store.getFloat("seekbarHeightDp", 24f));
            }
            if (view instanceof Button && !(view instanceof Switch)) {
                params.height = dp(view, store.getFloat("buttonHeightDp", 48f));
            }
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                int[] original = (int[]) view.getTag(R.id.design_base_margins);
                if (original == null) {
                    original = new int[]{margins.leftMargin, margins.topMargin,
                            margins.rightMargin, margins.bottomMargin};
                    view.setTag(R.id.design_base_margins, original);
                }
                margins.setMargins(scaleSpacing(view, original[0], spacing, store),
                        scaleSpacing(view, original[1], spacing, store),
                        scaleSpacing(view, original[2], spacing, store),
                        scaleSpacing(view, original[3], spacing, store));
            }
            view.setLayoutParams(params);
        }

        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Float original = (Float) view.getTag(R.id.design_base_text_size);
            if (original == null) {
                original = text.getTextSize();
                view.setTag(R.id.design_base_text_size, original);
            }
            float baseSp = original / view.getResources().getDisplayMetrics().scaledDensity;
            float adjustedSp = baseSp;
            if (near(baseSp, 24f)) adjustedSp = store.getFloat("textPageTitleSp", 24f);
            else if (near(baseSp, 16f)) adjustedSp = store.getFloat("textSectionTitleSp", 16f);
            else if (near(baseSp, 14f)) adjustedSp = store.getFloat("textBodySp", 14f);
            else if (near(baseSp, 12f)) adjustedSp = store.getFloat("textSecondarySp", 12f);
            else if (near(baseSp, 13f)) adjustedSp = store.getFloat("textLabelSp", 13f);
            else if (near(baseSp, 15f)) adjustedSp = store.getFloat("textButtonSp", 15f);
            else if (near(baseSp, 44f)) adjustedSp = store.getFloat("textLargeValueSp", 44f);
            else if (near(baseSp, 28f)) adjustedSp = store.getFloat("textIconButtonSp", 28f);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, adjustedSp * textScale);
        }

        if (view.getId() == R.id.protection_app_icon && params != null) {
            int size = dp(view, store.getFloat("controlSizeLargeDp", 40f));
            params.width = size;
            params.height = size;
            view.setLayoutParams(params);
        } else if (view.getId() == R.id.protection_separator && params != null) {
            params.height = dp(view, store.getFloat("protectionSeparatorHeightDp", 28f));
            view.setLayoutParams(params);
        } else if (view.getId() == R.id.sidebar_container && params != null) {
            params.width = dp(view, store.getFloat("sidebarWidthDp", 260f));
            view.setLayoutParams(params);
        } else if (view.getId() == R.id.header_container && params != null) {
            params.height = dp(view, store.getFloat("headerHeightDp", 80f));
            view.setLayoutParams(params);
        } else if (view.getId() == R.id.brand_header_container && params != null) {
            params.height = dp(view, store.getFloat("brandHeaderHeightDp", 72f));
            view.setLayoutParams(params);
        } else if (view.getId() == R.id.brand_mark_view && params != null) {
            int mark = dp(view, store.getFloat("brandMarkSizeDp", 44f));
            params.width = mark;
            params.height = mark;
            view.setLayoutParams(params);
            if (view.getBackground() instanceof GradientDrawable) {
                ((GradientDrawable) view.getBackground().mutate()).setCornerRadius(
                        dp(view, store.getFloat("brandMarkRadiusDp", 6f)));
            }
        } else if (view.getId() == R.id.sidebar_item_root && params != null) {
            params.height = dp(view, store.getFloat("sidebarItemHeightDp", 60f));
            view.setLayoutParams(params);
            int itemPadding = dp(view, store.getFloat("sidebarItemPaddingDp", 12f) * spacing);
            view.setPadding(itemPadding, view.getPaddingTop(), itemPadding, view.getPaddingBottom());
        } else if (view.getId() == R.id.module_icon) {
            int iconSize = dp(view, store.getFloat("sidebarIconSizeDp", 36f));
            if (params != null) {
                params.width = iconSize;
                params.height = iconSize;
                view.setLayoutParams(params);
            }
            int iconPadding = dp(view, store.getFloat("sidebarIconPaddingDp", 8f) * spacing);
            view.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
        } else if (view.getId() == R.id.module_state && params != null) {
            int size = dp(view, store.getFloat("statusDotSizeDp", 8f));
            params.width = size;
            params.height = size;
            view.setLayoutParams(params);
        } else if (view instanceof Button && !(view instanceof Switch) && params != null) {
            ((Button) view).setMinWidth(dp(view, store.getFloat("buttonMinWidthDp", 120f)));
        } else if (view instanceof Switch) {
            Switch toggle = (Switch) view;
            toggle.setMinWidth(dp(view, store.getFloat("switchMinWidthDp", 72f)));
            toggle.setSwitchMinWidth(dp(view, store.getFloat("switchTrackMinWidthDp", 56f)));
        }

        if (view.getParent() instanceof ScrollView) {
            int page = dp(view, store.getFloat("pagePaddingDp", 24f) * spacing);
            view.setPadding(page, page, page, page);
        }

        if (view instanceof ViewGroup && view.getBackground() instanceof GradientDrawable) {
            int card = dp(view, store.getFloat("cardPaddingDp", 16f) * spacing);
            view.setPadding(card, card, card, card);
        }
    }

    private static int scaleSpacing(View view, int pixels, float scale, DesignStore store) {
        if (pixels == 0) return 0;
        float density = view.getResources().getDisplayMetrics().density;
        float baseDp = pixels / density;
        float[] bases = {4f, 8f, 12f, 16f, 20f, 24f};
        String[] keys = {"spacingXsDp", "spacingSmDp", "spacingMdDp",
                "spacingLgDp", "spacingXlDp", "spacingXxlDp"};
        for (int i = 0; i < bases.length; i++) {
            if (near(baseDp, bases[i])) {
                return dp(view, store.getFloat(keys[i], bases[i]) * scale);
            }
        }
        return scale(pixels, scale);
    }

    private static boolean near(float value, float target) {
        return Math.abs(value - target) < 0.35f;
    }

    private static int scaleLayout(int value, float scale) {
        return value > 0 ? scale(value, scale) : value;
    }

    private static int scale(int value, float scale) {
        return Math.round(value * scale);
    }

    private static int dp(View view, float value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
