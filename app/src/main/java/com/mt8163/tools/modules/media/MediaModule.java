package com.mt8163.tools.modules.media;
import com.mt8163.tools.R; import com.mt8163.tools.core.ModuleContract;
public final class MediaModule implements ModuleContract {private boolean enabled; public String getId(){return "media";} public String getName(){return "Media";} public int getLayoutRes(){return R.layout.module_media;} public boolean isEnabledByDefault(){return false;} public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}}
