package com.mt8163.tools.modules.events;
import com.mt8163.tools.R; import com.mt8163.tools.core.ModuleContract;
public final class EventsModule implements ModuleContract {private boolean enabled; public String getId(){return "events";} public String getName(){return "Events";} public int getLayoutRes(){return R.layout.module_events;} public boolean isEnabledByDefault(){return true;} public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}}
