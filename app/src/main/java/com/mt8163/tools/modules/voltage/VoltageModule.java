package com.mt8163.tools.modules.voltage;
import com.mt8163.tools.R; import com.mt8163.tools.core.ModuleContract;
public final class VoltageModule implements ModuleContract {private boolean enabled; public String getId(){return "voltage";} public String getName(){return "Voltage";} public int getLayoutRes(){return R.layout.module_voltage;} public boolean isEnabledByDefault(){return false;} public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}}
