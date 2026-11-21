package com.mt8163.tools.modules.events;
import android.content.Context;import android.os.Handler;import android.os.Looper;import java.util.Collections;import java.util.List;import java.util.concurrent.ExecutorService;import java.util.concurrent.Executors;
public final class EventManager {
 public interface Listener{void onEventsChanged();}
 private static volatile EventManager instance;private final EventDatabase database;private final ExecutorService writer=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());private volatile boolean enabled;private volatile Listener listener;
 private EventManager(Context c){database=new EventDatabase(c);enabled=c.getSharedPreferences("mt8163_tools",Context.MODE_PRIVATE).getBoolean("module_events",true);}
 public static EventManager get(Context c){if(instance==null)synchronized(EventManager.class){if(instance==null)instance=new EventManager(c.getApplicationContext());}return instance;}
 public void setEnabled(boolean v){enabled=v;}public boolean isEnabled(){return enabled;}public void setListener(Listener v){listener=v;}
 public void log(String m,String s,String msg){if(!enabled||m==null||m.trim().isEmpty()||s==null||msg==null)return;enqueue(m.trim(),s.trim().isEmpty()?"General":s.trim(),msg.trim(),false);}
 public void logLifecycle(String s,String msg){if(s!=null&&msg!=null)enqueue("Events",s,msg,true);}
 private void enqueue(String m,String s,String msg,boolean lifecycle){if(msg.isEmpty())return;writer.execute(()->{if(!lifecycle&&!enabled)return;try{database.insert(System.currentTimeMillis(),m,s,msg);main.post(()->{Listener l=listener;if(enabled&&l!=null)l.onEventsChanged();});}catch(RuntimeException ignored){}});}
 public List<EventRecord> read(List<String> m,int limit,long before){return enabled?database.read(m,limit,before):Collections.emptyList();}
 public List<EventRecord> readAll(List<String> m){return enabled?database.readAll(m):Collections.emptyList();}
 public List<String> getModules(){return enabled?database.getModules():Collections.emptyList();}
 public int count(){return enabled?database.count():0;}
 public void clear(){if(!enabled)return;writer.execute(()->{if(enabled)database.clear();main.post(()->{Listener l=listener;if(enabled&&l!=null)l.onEventsChanged();});});}
}