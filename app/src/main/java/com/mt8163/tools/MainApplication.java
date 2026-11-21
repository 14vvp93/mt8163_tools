package com.mt8163.tools;
import android.app.Application;
import com.mt8163.tools.core.ModuleRegistry;
import com.mt8163.tools.core.PreferencesManager;
public final class MainApplication extends Application {
 private ModuleRegistry registry; private PreferencesManager preferences;
 @Override public void onCreate(){super.onCreate();preferences=new PreferencesManager(this);registry=new ModuleRegistry();registry.loadStates(preferences);}
 public ModuleRegistry getModuleRegistry(){return registry;}
 public PreferencesManager getPreferencesManager(){return preferences;}
}
