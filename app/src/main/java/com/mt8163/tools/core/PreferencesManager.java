package com.mt8163.tools.core;
import android.content.Context; import android.content.SharedPreferences;
public final class PreferencesManager {
 private final SharedPreferences prefs;
 public PreferencesManager(Context c){prefs=c.getApplicationContext().getSharedPreferences("mt8163_tools",Context.MODE_PRIVATE);}
 public boolean getModuleEnabled(String id,boolean fallback){return prefs.getBoolean("module_"+id,fallback);}
 public void setModuleEnabled(String id,boolean enabled){prefs.edit().putBoolean("module_"+id,enabled).commit();}
}
