package com.mt8163.tools.ui;

import android.app.Activity;
import android.app.Dialog;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public final class DialogHelper {
    private static final int SELECTION_WIDTH_DP = 760;
    private static final int SELECTION_MAX_HEIGHT_DP = 580;
    private static final int COMPACT_WIDTH_DP = 480;
    private static final int SCREEN_MARGIN_DP = 48;

    private static Dialog activeDialog;

    private DialogHelper() {
    }

    public static void showSelection(Activity activity, Dialog dialog, View content) {
        show(activity, dialog, content, SELECTION_WIDTH_DP, SELECTION_MAX_HEIGHT_DP);
    }

    public static void showCompact(Activity activity, Dialog dialog, View content) {
        show(activity, dialog, content, COMPACT_WIDTH_DP, 0);
    }

    private static synchronized void show(Activity activity, Dialog dialog, View content,
                                           int maxWidthDp, int maxHeightDp) {
        if (activity == null || dialog == null || content == null
                || activity.isFinishing() || activity.isDestroyed()
                || dialog.isShowing()) return;

        if (activeDialog != null && activeDialog.isShowing()) return;
        activeDialog = null;

        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(dismissedDialog -> {
            synchronized (DialogHelper.class) {
                if (activeDialog == dismissedDialog) activeDialog = null;
            }
        });

        activeDialog = dialog;
        try {
            dialog.show();
        } catch (RuntimeException error) {
            activeDialog = null;
            throw error;
        }

        Window window = dialog.getWindow();
        if (window == null) return;

        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);

        int margin = dp(metrics, SCREEN_MARGIN_DP);
        int availableWidth = Math.max(1, metrics.widthPixels - margin);
        int width = Math.min(dp(metrics, maxWidthDp), availableWidth);
        int height = maxHeightDp > 0
                ? Math.min(dp(metrics, maxHeightDp), Math.max(1, metrics.heightPixels - margin))
                : WindowManager.LayoutParams.WRAP_CONTENT;

        window.setLayout(width, height);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private static int dp(DisplayMetrics metrics, int value) {
        return Math.round(value * metrics.density);
    }
}
