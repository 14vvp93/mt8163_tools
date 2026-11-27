package com.mt8163.tools.modules.events;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;
import com.mt8163.tools.modules.ModuleUiContract;

import java.util.List;

public final class EventsModule implements ModuleContract, ModuleUiContract {
    private boolean enabled;

    @Override public String getId() { return "events"; }
    @Override public int getNameRes() { return R.string.module_events_title; }
    @Override public int getLayoutRes() { return R.layout.module_events; }
    @Override public boolean isEnabledByDefault() { return true; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean value) { enabled = value; }

    @Override
    public View createContentView(Activity activity, FrameLayout container,
                                  List<ModuleContract> modules) {
        return isEnabled() ? EventsModuleView.create(activity, modules) : null;
    }

    @Override
    public void onEnabledChanged(Activity activity, boolean enabled) {
        EventManager manager = EventManager.get(activity);
        manager.setEnabled(enabled);
        manager.logLifecycle(enabled ? "moduleEnabled" : "moduleDisabled",
                activity.getString(enabled
                        ? R.string.events_module_enabled
                        : R.string.events_module_disabled));
    }

    @Override
    public void onContentRemoved() {
        EventsModuleView.destroy();
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        return EventsModuleView.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean recreateContentOnEnabledChange() {
        return true;
    }
}
