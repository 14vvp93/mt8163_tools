package com.mt8163.tools;

import android.app.Application;

import com.mt8163.tools.core.ModuleRegistry;
import com.mt8163.tools.core.PreferencesManager;
import com.mt8163.tools.core.design.DesignStore;
import com.mt8163.tools.modules.events.EventManager;
import com.mt8163.tools.modules.media.MediaSettingsStore;
import com.mt8163.tools.modules.protection.ProtectionSettingsStore;
import com.mt8163.tools.modules.voltage.VoltageSettingsStore;

public final class MainApplication extends Application {
    private ModuleRegistry registry;
    private PreferencesManager preferences;
    private DesignStore designStore;

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = new PreferencesManager(this);
        registry = new ModuleRegistry();
        registry.loadStates(preferences);
        designStore = new DesignStore(this);
        EventManager.get(this).setEnabled(preferences.getModuleEnabled("events", true));
        VoltageSettingsStore.publish(this);
        MediaSettingsStore.publish(this);
        ProtectionSettingsStore.publish(this);
    }

    public ModuleRegistry getModuleRegistry() {
        return registry;
    }

    public PreferencesManager getPreferencesManager() {
        return preferences;
    }

    public DesignStore getDesignStore() {
        return designStore;
    }
}
