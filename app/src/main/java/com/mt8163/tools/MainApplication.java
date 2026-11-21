package com.mt8163.tools;
import android.app.Application;import android.content.IntentFilter;import android.os.Build;import com.mt8163.tools.core.ModuleRegistry;import com.mt8163.tools.core.PreferencesManager;import com.mt8163.tools.modules.events.EventLogger;import com.mt8163.tools.modules.events.EventManager;import com.mt8163.tools.modules.events.EventsReceiver;import com.mt8163.tools.modules.voltage.VoltageSettingsStore;
public final class MainApplication extends Application {
 private ModuleRegistry registry;private PreferencesManager preferences;private EventsReceiver receiver;
 @Override public void onCreate(){super.onCreate();preferences=new PreferencesManager(this);registry=new ModuleRegistry();registry.loadStates(preferences);EventManager.get(this).setEnabled(preferences.getModuleEnabled("events",true));receiver=new EventsReceiver();IntentFilter filter=new IntentFilter(EventLogger.ACTION_EVENT);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,RECEIVER_EXPORTED);else registerReceiver(receiver,filter);VoltageSettingsStore.publish(this);}
 @Override public void onTerminate(){if(receiver!=null)unregisterReceiver(receiver);super.onTerminate();}
 public ModuleRegistry getModuleRegistry(){return registry;}public PreferencesManager getPreferencesManager(){return preferences;}
}