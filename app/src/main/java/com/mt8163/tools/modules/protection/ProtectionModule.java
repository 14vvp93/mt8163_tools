package com.mt8163.tools.modules.protection;
import com.mt8163.tools.R; import com.mt8163.tools.core.ModuleContract;
public final class ProtectionModule implements ModuleContract {private boolean enabled; public String getId(){return "protection";} public String getName(){return "Protection";} public int getLayoutRes(){return R.layout.module_protection;} public boolean isEnabledByDefault(){return false;} public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}}
