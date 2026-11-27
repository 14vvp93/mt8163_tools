package com.mt8163.tools.core;

import com.mt8163.tools.modules.autorun.AutorunModule;
import com.mt8163.tools.modules.events.EventsModule;
import com.mt8163.tools.modules.media.MediaModule;
import com.mt8163.tools.modules.protection.ProtectionModule;
import com.mt8163.tools.modules.voltage.VoltageModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleRegistry {
    private final List<ModuleContract> registeredModules;

    public ModuleRegistry() {
        List<ModuleContract> modules = new ArrayList<>();
        modules.add(new MediaModule());
        modules.add(new ProtectionModule());
        modules.add(new VoltageModule());
        modules.add(new AutorunModule());
        modules.add(new EventsModule());
        registeredModules = Collections.unmodifiableList(modules);
    }

    public List<ModuleContract> getModules() {
        return registeredModules;
    }

    public ModuleContract findById(String moduleId) {
        if (moduleId == null) return null;

        for (ModuleContract module : registeredModules) {
            if (module.getId().equals(moduleId)) return module;
        }
        return null;
    }

    public void loadStates(PreferencesManager preferencesManager) {
        for (ModuleContract module : registeredModules) {
            module.setEnabled(preferencesManager.getModuleEnabled(
                    module.getId(), module.isEnabledByDefault()));
        }
    }

    public void saveState(ModuleContract module, PreferencesManager preferencesManager) {
        if (module == null || preferencesManager == null) return;
        preferencesManager.setModuleEnabled(module.getId(), module.isEnabled());
    }
}
