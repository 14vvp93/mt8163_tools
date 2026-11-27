package com.mt8163.tools;

import android.app.Application;

import com.mt8163.tools.core.ModuleRegistry;
import com.mt8163.tools.core.PreferencesManager;
import com.mt8163.tools.modules.events.EventManager;
import com.mt8163.tools.modules.media.MediaSettingsStore;
import com.mt8163.tools.modules.protection.ProtectionSettingsStore;
import com.mt8163.tools.modules.voltage.VoltageSettingsStore;

public final class MainApplication extends Application {
    private ModuleRegistry moduleRegistry;
    private PreferencesManager preferencesManager;

    @Override
    public void onCreate() {
        super.onCreate();
        preferencesManager = new PreferencesManager(this);
        moduleRegistry = new ModuleRegistry();
        moduleRegistry.loadStates(preferencesManager);
        EventManager.get(this).setEnabled(preferencesManager.getModuleEnabled("events", true));
        VoltageSettingsStore.publish(this);
        MediaSettingsStore.publish(this);
        ProtectionSettingsStore.publish(this);
    }

    public ModuleRegistry getModuleRegistry() {
        return moduleRegistry;
    }

    public PreferencesManager getPreferencesManager() {
        return preferencesManager;
    }
}
