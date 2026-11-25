package com.mt8163.tools.modules.design;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;

public final class DesignModule implements ModuleContract {
    private boolean enabled;

    @Override public String getId() { return "design"; }
    @Override public int getNameRes() { return R.string.module_design_title; }
    @Override public int getLayoutRes() { return R.layout.module_design; }
    @Override public boolean isEnabledByDefault() { return true; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean value) { enabled = value; }
}
