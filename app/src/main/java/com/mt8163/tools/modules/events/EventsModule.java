package com.mt8163.tools.modules.events;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;

public final class EventsModule implements ModuleContract {
    private boolean enabled;

    @Override public String getId() { return "events"; }
    @Override public int getNameRes() { return R.string.module_events_title; }
    @Override public int getLayoutRes() { return R.layout.module_events; }
    @Override public boolean isEnabledByDefault() { return true; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean value) { enabled = value; }
}
