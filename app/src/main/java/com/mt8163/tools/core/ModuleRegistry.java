package com.mt8163.tools.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleRegistry {
    private final List<ModuleContract> registeredModules;

    public ModuleRegistry(List<ModuleContract> modules) {
        if (modules == null) throw new IllegalArgumentException("Modules must not be null");
        registeredModules = Collections.unmodifiableList(new ArrayList<>(modules));
    }

    public List<ModuleContract> getModules() {
        return registeredModules;
    }

    public ModuleContract findById(String moduleId) {
        if (moduleId == null) return null;
        for (ModuleContract module : registeredModules) {
            if (module != null && moduleId.equals(module.getId())) return module;
        }
        return null;
    }

    public void loadStates(PreferencesManager preferencesManager) {
        if (preferencesManager == null) return;
        for (ModuleContract module : registeredModules) {
            if (module != null) {
                module.setEnabled(preferencesManager.getModuleEnabled(
                        module.getId(), module.isEnabledByDefault()));
            }
        }
    }

    public void saveState(ModuleContract module, PreferencesManager preferencesManager) {
        if (module == null || preferencesManager == null) return;
        preferencesManager.setModuleEnabled(module.getId(), module.isEnabled());
    }
}
