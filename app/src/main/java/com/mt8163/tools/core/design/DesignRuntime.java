package com.mt8163.tools.core.design;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

public final class DesignRuntime {
    private DesignRuntime() {
    }

    public static void attach(View root, DesignStore store) {
        if (root == null || store == null) return;
        applyTree(root, store);
        attachHierarchy(root, store);
    }

    public static void applyTree(View root, DesignStore store) {
        if (root == null || store == null) return;
        applyView(root, store);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTree(group.getChildAt(i), store);
            }
        }
    }

    private static void attachHierarchy(View view, DesignStore store) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        if (!Boolean.TRUE.equals(group.getTag(com.mt8163.tools.R.id.design_listener_attached))) {
            group.setTag(com.mt8163.tools.R.id.design_listener_attached, Boolean.TRUE);
            group.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    applyTree(child, store);
                    attachHierarchy(child, store);
                }

                @Override
                public void onChildViewRemoved(View parent, View child) {
                }
            });
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            attachHierarchy(group.getChildAt(i), store);
        }
    }

    private static void applyView(View view, DesignStore store) {
        if (view.getId() == View.NO_ID) return;

        String name;
        try {
            name = view.getResources().getResourceEntryName(view.getId());
        } catch (RuntimeException error) {
            return;
        }

        JSONObject style = store.getStyle(name);
        if (style.length() == 0) return;

        if (style.has("backgroundColor")) {
            int color = style.optInt("backgroundColor", Color.TRANSPARENT);
            view.setBackgroundTintList(ColorStateList.valueOf(color));
        }

        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (style.has("textColor")) {
                text.setTextColor(style.optInt("textColor", Color.WHITE));
            }
            if (style.has("textSizeSp")) {
                float size = (float) style.optDouble("textSizeSp", 0);
                if (size > 0) text.setTextSize(size);
            }
        }

        if (view instanceof ImageView && style.has("imageTintColor")) {
            ((ImageView) view).setImageTintList(
                    ColorStateList.valueOf(style.optInt("imageTintColor", Color.WHITE)));
        }

        if (style.has("alpha")) {
            float alpha = (float) style.optDouble("alpha", 1);
            view.setAlpha(Math.max(0f, Math.min(1f, alpha)));
        }

        if (style.has("paddingDp")) {
            int padding = dp(view, style.optInt("paddingDp", 0));
            view.setPadding(padding, padding, padding, padding);
        }

        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            boolean changed = false;
            if (style.has("widthDp")) {
                params.width = layoutSize(style.optInt("widthDp", -2), view);
                changed = true;
            }
            if (style.has("heightDp")) {
                params.height = layoutSize(style.optInt("heightDp", -2), view);
                changed = true;
            }
            if (params instanceof ViewGroup.MarginLayoutParams && style.has("marginDp")) {
                int margin = dp(view, style.optInt("marginDp", 0));
                ((ViewGroup.MarginLayoutParams) params).setMargins(
                        margin, margin, margin, margin);
                changed = true;
            }
            if (changed) view.setLayoutParams(params);
        }

        if (style.has("cornerRadiusDp")) {
            Drawable background = view.getBackground();
            if (background instanceof GradientDrawable) {
                GradientDrawable shape = (GradientDrawable) background.mutate();
                shape.setCornerRadius(dp(view,
                        style.optInt("cornerRadiusDp", 0)));
                view.setBackground(shape);
            }
        }
    }

    private static int layoutSize(int value, View view) {
        if (value == -1) return ViewGroup.LayoutParams.MATCH_PARENT;
        if (value == -2) return ViewGroup.LayoutParams.WRAP_CONTENT;
        return dp(view, Math.max(0, value));
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
