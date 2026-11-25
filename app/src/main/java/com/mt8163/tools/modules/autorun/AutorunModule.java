package com.mt8163.tools.modules.autorun;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;

public final class AutorunModule implements ModuleContract {
    private boolean enabled;

    @Override public String getId() { return "autorun"; }
    @Override public int getNameRes() { return R.string.module_autorun_title; }
    @Override public int getLayoutRes() { return R.layout.module_autorun; }
    @Override public boolean isEnabledByDefault() { return false; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean value) { enabled = value; }
}
