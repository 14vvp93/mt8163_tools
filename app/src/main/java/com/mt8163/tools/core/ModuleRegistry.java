package com.mt8163.tools.core;
import com.mt8163.tools.modules.media.MediaModule;
import com.mt8163.tools.modules.protection.ProtectionModule;
import com.mt8163.tools.modules.voltage.VoltageModule;
import com.mt8163.tools.modules.autorun.AutorunModule;
import com.mt8163.tools.modules.events.EventsModule;
import java.util.ArrayList; import java.util.Collections; import java.util.List;
public final class ModuleRegistry {
 private final List<ModuleContract> modules;
 public ModuleRegistry(){List<ModuleContract> list=new ArrayList<>();list.add(new MediaModule());list.add(new ProtectionModule());list.add(new VoltageModule());list.add(new AutorunModule());list.add(new EventsModule());modules=Collections.unmodifiableList(list);}
 public List<ModuleContract> getModules(){return modules;}
 public ModuleContract findById(String id){if(id==null)return null;for(ModuleContract m:modules)if(m.getId().equals(id))return m;return null;}
 public void loadStates(PreferencesManager p){for(ModuleContract m:modules)m.setEnabled(p.getModuleEnabled(m.getId(),m.isEnabledByDefault()));}
 public void saveState(ModuleContract m,PreferencesManager p){if(m!=null&&p!=null)p.setModuleEnabled(m.getId(),m.isEnabled());}
}
