package com.mt8163.tools.core;

import com.mt8163.tools.modules.autorun.AutorunModule;
import com.mt8163.tools.modules.design.DesignModule;
import com.mt8163.tools.modules.events.EventsModule;
import com.mt8163.tools.modules.media.MediaModule;
import com.mt8163.tools.modules.protection.ProtectionModule;
import com.mt8163.tools.modules.voltage.VoltageModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleRegistry {
    private final List<ModuleContract> modules;

    public ModuleRegistry() {
        List<ModuleContract> list = new ArrayList<>();
        list.add(new MediaModule());
        list.add(new ProtectionModule());
        list.add(new VoltageModule());
        list.add(new AutorunModule());
        list.add(new DesignModule());
        list.add(new EventsModule());
        modules = Collections.unmodifiableList(list);
    }

    public List<ModuleContract> getModules() {
        return modules;
    }

    public ModuleContract findById(String id) {
        if (id == null) return null;
        for (ModuleContract module : modules) {
            if (module.getId().equals(id)) return module;
        }
        return null;
    }

    public void loadStates(PreferencesManager preferences) {
        for (ModuleContract module : modules) {
            module.setEnabled(preferences.getModuleEnabled(
                    module.getId(), module.isEnabledByDefault()));
        }
    }

    public void saveState(ModuleContract module, PreferencesManager preferences) {
        if (module != null && preferences != null) {
            preferences.setModuleEnabled(module.getId(), module.isEnabled());
        }
    }
}
