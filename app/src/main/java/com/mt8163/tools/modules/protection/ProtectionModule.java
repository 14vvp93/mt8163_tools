package com.mt8163.tools.modules.protection;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import com.mt8163.tools.R;
import com.mt8163.tools.core.EventLogger;
import com.mt8163.tools.core.ModuleContract;
import com.mt8163.tools.modules.ModuleUiContract;

import java.util.List;

public final class ProtectionModule implements ModuleContract, ModuleUiContract {
    private boolean enabled;

    @Override public String getId() { return "protection"; }
    @Override public int getNameRes() { return R.string.module_protection_title; }
    @Override public int getLayoutRes() { return R.layout.module_protection; }
    @Override public boolean isEnabledByDefault() { return false; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean value) { enabled = value; }

    @Override
    public View createContentView(Activity activity, FrameLayout container,
                                  List<ModuleContract> modules) {
        View view = LayoutInflater.from(activity)
                .inflate(getLayoutRes(), container, false);
        new ProtectionModuleView(view, activity);
        return view;
    }

    @Override
    public void onEnabledChanged(Activity activity, boolean enabled) {
        EventLogger.log(activity, "Protection",
                enabled ? "moduleEnabled" : "moduleDisabled",
                activity.getString(enabled
                        ? R.string.protection_module_enabled
                        : R.string.protection_module_disabled));
        ProtectionSettingsStore.publish(activity);
    }
}
