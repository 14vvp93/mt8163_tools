package com.mt8163.tools.core.design;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.mt8163.tools.R;

import org.json.JSONObject;

public final class DesignRuntime {
    private DesignRuntime() {
    }

    public static void attach(View root, DesignStore store, String scope) {
        if (root == null || store == null) return;
        assignKeys(root, scope == null ? "global" : scope, "root");
        applyTree(root, store);
        attachHierarchy(root, store, scope == null ? "global" : scope);
    }

    public static String getViewKey(View view) {
        Object key = view == null ? null : view.getTag(R.id.design_runtime_key);
        return key instanceof String ? (String) key : null;
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

    private static void assignKeys(View view, String scope, String path) {
        String key = resourceKey(view);
        view.setTag(R.id.design_runtime_key,
                key == null ? "path:" + scope + ":" + path : key);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                assignKeys(group.getChildAt(i), scope, path + "/" + i);
            }
        }
    }

    private static void attachHierarchy(View view, DesignStore store, String scope) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        Object attachedScope = group.getTag(R.id.design_listener_scope);
        if (!scope.equals(attachedScope)) {
            group.setTag(R.id.design_listener_scope, scope);
            group.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    int index = ((ViewGroup) parent).indexOfChild(child);
                    Object parentPath = parent.getTag(R.id.design_runtime_key);
                    String path = parentPath instanceof String
                            ? ((String) parentPath).substring(((String) parentPath).lastIndexOf(':') + 1) : "root";
                    assignKeys(child, scope, path + "/" + index);
                    applyTree(child, store);
                    attachHierarchy(child, store, scope);
                }

                @Override
                public void onChildViewRemoved(View parent, View child) {
                }
            });
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            attachHierarchy(group.getChildAt(i), store, scope);
        }
    }

    private static String resourceKey(View view) {
        if (view.getId() == View.NO_ID) return null;
        try {
            return "id:" + view.getResources().getResourceEntryName(view.getId());
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static void applyView(View view, DesignStore store) {
        String key = getViewKey(view);
        if (key == null) return;
        JSONObject style = store.getStyle(key);
        if (style.length() == 0) return;

        if (style.has("backgroundColor")) {
            view.setBackgroundTintList(ColorStateList.valueOf(
                    style.optInt("backgroundColor", Color.TRANSPARENT)));
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
        if (style.has("rotation")) {
            view.setRotation((float) style.optDouble("rotation", 0));
        }
        if (style.has("elevationDp")) {
            view.setElevation(dp(view, style.optInt("elevationDp", 0)));
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
                shape.setCornerRadius(dp(view, style.optInt("cornerRadiusDp", 0)));
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
