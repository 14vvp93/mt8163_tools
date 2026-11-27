package com.mt8163.tools.ui;

import android.app.Activity;
import android.app.Dialog;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public final class DialogHelper {
    private static final int DIALOG_WIDTH_DP = 720;
    private static final int DIALOG_MAX_HEIGHT_DP = 560;
    private static final int SCREEN_MARGIN_DP = 48;

    private DialogHelper() {
    }

    public static void show(Activity activity, Dialog dialog, View content, boolean large) {
        if (activity == null || dialog == null || content == null || activity.isFinishing()) return;

        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();

        Window window = dialog.getWindow();
        if (window == null) return;

        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);

        int margin = dp(metrics, SCREEN_MARGIN_DP);
        int width = Math.min(dp(metrics, DIALOG_WIDTH_DP), metrics.widthPixels - margin);
        int height = large
                ? Math.min(dp(metrics, DIALOG_MAX_HEIGHT_DP), metrics.heightPixels - margin)
                : WindowManager.LayoutParams.WRAP_CONTENT;

        window.setLayout(Math.max(1, width), height);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private static int dp(DisplayMetrics metrics, int value) {
        return Math.round(value * metrics.density);
    }
}
