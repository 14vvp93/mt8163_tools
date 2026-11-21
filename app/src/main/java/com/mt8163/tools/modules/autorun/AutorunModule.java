package com.mt8163.tools.modules.autorun;
import com.mt8163.tools.R; import com.mt8163.tools.core.ModuleContract;
public final class AutorunModule implements ModuleContract {private boolean enabled; public String getId(){return "autorun";} public String getName(){return "Autorun";} public int getLayoutRes(){return R.layout.module_autorun;} public boolean isEnabledByDefault(){return false;} public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}}
