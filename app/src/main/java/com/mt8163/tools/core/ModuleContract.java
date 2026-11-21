package com.mt8163.tools.core;
public interface ModuleContract {
 String getId(); String getName(); int getLayoutRes(); boolean isEnabledByDefault(); boolean isEnabled(); void setEnabled(boolean enabled);
}
