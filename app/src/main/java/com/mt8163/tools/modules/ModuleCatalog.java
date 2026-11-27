package com.mt8163.tools.modules;

import com.mt8163.tools.core.ModuleContract;
import com.mt8163.tools.modules.autorun.AutorunModule;
import com.mt8163.tools.modules.events.EventsModule;
import com.mt8163.tools.modules.media.MediaModule;
import com.mt8163.tools.modules.protection.ProtectionModule;
import com.mt8163.tools.modules.voltage.VoltageModule;

import java.util.ArrayList;
import java.util.List;

public final class ModuleCatalog {
    private ModuleCatalog() {
    }

    public static List<ModuleContract> create() {
        List<ModuleContract> modules = new ArrayList<>();
        modules.add(new MediaModule());
        modules.add(new ProtectionModule());
        modules.add(new VoltageModule());
        modules.add(new AutorunModule());
        modules.add(new EventsModule());
        return modules;
    }
}
