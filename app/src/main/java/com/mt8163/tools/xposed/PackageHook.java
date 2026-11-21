package com.mt8163.tools.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

public interface PackageHook {
    boolean supports(String packageName);

    void install(XC_LoadPackage.LoadPackageParam param) throws Throwable;
}
