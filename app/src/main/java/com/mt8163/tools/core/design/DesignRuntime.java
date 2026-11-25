package com.mt8163.tools.core.design;

import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
        applyDimensions(view, store.getFloat("spacingScale", 1f),
                store.getFloat("sizeScale", 1f), store.getFloat("cornerRadiusDp", 8f));
    }

    private static void applyColors(View view, DesignStore store) {
        int app = view.getContext().getColor(R.color.app_background);
        int sidebar = view.getContext().getColor(R.color.sidebar_background);
        int header = view.getContext().getColor(R.color.header_background);
        int surface = view.getContext().getColor(R.color.panel_background);
        int accent = view.getContext().getColor(R.color.neon_red);
        int primary = view.getContext().getColor(R.color.text_primary);
        int secondary = view.getContext().getColor(R.color.text_secondary);
        int muted = view.getContext().getColor(R.color.text_muted);

        if (view instanceof TextView) {
            TextView text = (TextView) view;
            ColorStateList base = (ColorStateList) view.getTag(R.id.design_base_text_color);
            if (base == null) {
                base = text.getTextColors();
                view.setTag(R.id.design_base_text_color, base);
            }
            text.setTextColor(mapColors(base, app, sidebar, header, surface, accent, primary, secondary, muted, store));
        }

        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            ColorStateList base = (ColorStateList) view.getTag(R.id.design_base_image_tint);
            if (base == null && image.getImageTintList() != null) {
                base = image.getImageTintList();
                view.setTag(R.id.design_base_image_tint, base);
            }
            if (base != null) image.setImageTintList(
                    mapColors(base, app, sidebar, header, surface, accent, primary, secondary, muted, store));
        }

        ColorStateList baseTint = (ColorStateList) view.getTag(R.id.design_base_background_tint);
        if (baseTint == null && view.getBackgroundTintList() != null) {
            baseTint = view.getBackgroundTintList();
            view.setTag(R.id.design_base_background_tint, baseTint);
        }
        if (baseTint != null) view.setBackgroundTintList(ColorStateList.valueOf(
                mapColor(baseTint.getDefaultColor(), app, sidebar, header, surface, accent, primary, secondary, muted, store)));

        Drawable background = view.getBackground();
        if (background instanceof ColorDrawable) {
            ColorDrawable color = (ColorDrawable) background.mutate();
            Integer base = (Integer) view.getTag(R.id.design_base_background_color);
            if (base == null) {
                base = color.getColor();
                view.setTag(R.id.design_base_background_color, base);
            }
            color.setColor(mapColor(base, app, sidebar, header, surface, accent, primary, secondary, muted, store));
        } else if (background instanceof GradientDrawable && view instanceof ViewGroup) {
            Drawable shape = background.mutate();
            shape.setTintList(ColorStateList.valueOf(store.getColor("surfaceColor", surface)));
            view.setBackground(shape);
        }
    }

    private static ColorStateList mapColors(ColorStateList base, int app, int sidebar,
                                               int header, int surface, int accent,
                                               int primary, int secondary, int muted,
                                               DesignStore store) {
        int[] colors = base.getColors().clone();
        for (int i = 0; i < colors.length; i++) {
            colors[i] = mapColor(colors[i], app, sidebar, header, surface,
                    accent, primary, secondary, muted, store);
        }
        return new ColorStateList(base.getStates(), colors);
    }

    private static int mapColor(int color, int app, int sidebar, int header, int surface,
                                int accent, int primary, int secondary, int muted, DesignStore store) {
        if (color == app) return store.getColor("appColor", app);
        if (color == sidebar) return store.getColor("sidebarColor", sidebar);
        if (color == header) return store.getColor("headerColor", header);
        if (color == surface) return store.getColor("surfaceColor", surface);
        if (color == accent) return store.getColor("accentColor", accent);
        if (color == primary) return store.getColor("primaryTextColor", primary);
        if (color == secondary || color == muted) return store.getColor("secondaryTextColor", secondary);
        return color;
    }

    private static void applyDimensions(View view, float spacingScale, float sizeScale, float radiusDp) {
        int[] padding = (int[]) view.getTag(R.id.design_base_padding);
        if (padding == null) {
            padding = new int[]{view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom()};
            view.setTag(R.id.design_base_padding, padding);
        }
        view.setPadding(scale(padding[0], spacingScale), scale(padding[1], spacingScale),
                scale(padding[2], spacingScale), scale(padding[3], spacingScale));

        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            int[] dimensions = (int[]) view.getTag(R.id.design_base_dimensions);
            if (dimensions == null) {
                dimensions = new int[]{params.width, params.height};
                view.setTag(R.id.design_base_dimensions, dimensions);
            }
            params.width = scaleLayout(dimensions[0], sizeScale);
            params.height = scaleLayout(dimensions[1], sizeScale);
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                int[] base = (int[]) view.getTag(R.id.design_base_margins);
                if (base == null) {
                    base = new int[]{margins.leftMargin, margins.topMargin, margins.rightMargin, margins.bottomMargin};
                    view.setTag(R.id.design_base_margins, base);
                }
                margins.setMargins(scale(base[0], spacingScale), scale(base[1], spacingScale),
                        scale(base[2], spacingScale), scale(base[3], spacingScale));
            }
            view.setLayoutParams(params);
        }

        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Float base = (Float) view.getTag(R.id.design_base_text_size);
            if (base == null) {
                base = text.getTextSize();
                view.setTag(R.id.design_base_text_size, base);
            }
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * sizeScale);
        }

        Drawable background = view.getBackground();
        if (background instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) background.mutate();
            shape.setCornerRadius(Math.round(radiusDp * view.getResources().getDisplayMetrics().density));
            view.setBackground(shape);
        }
    }

    private static int scaleLayout(int value, float scale) {
        return value > 0 ? scale(value, scale) : value;
    }

    private static int scale(int value, float scale) {
        return Math.round(value * scale);
    }
}