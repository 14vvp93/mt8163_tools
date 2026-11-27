package com.mt8163.tools.modules.autorun;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;
import com.mt8163.tools.modules.ModuleUiContract;

import java.util.List;

public final class AutorunModule implements ModuleContract, ModuleUiContract {
    private boolean enabled;

    @Override public String getId() { return "autorun"; }
    @Override public int getNameRes() { return R.string.module_autorun_title; }
    @Override public int getLayoutRes() { return R.layout.module_autorun; }
    @Override public boolean isEnabledByDefault() { return false; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean value) { enabled = value; }

    @Override
    public View createContentView(Activity activity, FrameLayout container,
                                  List<ModuleContract> modules) {
        return LayoutInflater.from(activity)
                .inflate(getLayoutRes(), container, false);
    }
}
