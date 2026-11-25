package com.mt8163.tools.modules.protection;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;

public final class ProtectionModule implements ModuleContract {
    private boolean enabled;

    @Override public String getId() { return "protection"; }
    @Override public int getNameRes() { return R.string.module_protection_title; }
    @Override public int getLayoutRes() { return R.layout.module_protection; }
    @Override public boolean isEnabledByDefault() { return false; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean value) { enabled = value; }
}
