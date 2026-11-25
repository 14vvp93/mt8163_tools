package com.mt8163.tools.modules.voltage;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;

public final class VoltageModule implements ModuleContract {
    private boolean enabled;

    @Override public String getId() { return "voltage"; }
    @Override public int getNameRes() { return R.string.module_voltage_title; }
    @Override public int getLayoutRes() { return R.layout.module_voltage; }
    @Override public boolean isEnabledByDefault() { return false; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean value) { enabled = value; }
}
