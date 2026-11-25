package com.mt8163.tools.modules.media;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;

public final class MediaModule implements ModuleContract {
    private boolean enabled;

    @Override public String getId() { return "media"; }
    @Override public int getNameRes() { return R.string.module_media_title; }
    @Override public int getLayoutRes() { return R.layout.module_media; }
    @Override public boolean isEnabledByDefault() { return false; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean value) { enabled = value; }
}
