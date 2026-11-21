package com.mt8163.tools.modules.events;
import android.content.Context;import android.os.Handler;import android.os.Looper;import java.util.Collections;import java.util.List;import java.util.concurrent.ArrayBlockingQueue;import java.util.concurrent.RejectedExecutionException;import java.util.concurrent.ThreadPoolExecutor;import java.util.concurrent.TimeUnit;import java.util.concurrent.atomic.AtomicLong;
public final class EventManager {
 public interface Listener{void onEventsChanged();}
 private static volatile EventManager instance;private final EventDatabase database;private final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(512));private final Handler main=new Handler(Looper.getMainLooper());private final AtomicLong dropped=new AtomicLong();private volatile boolean enabled;private volatile Listener listener;
 private EventManager(Context c){database=new EventDatabase(c);enabled=c.getSharedPreferences("mt8163_tools",Context.MODE_PRIVATE).getBoolean("module_events",true);}
 public static EventManager get(Context c){if(instance==null)synchronized(EventManager.class){if(instance==null)instance=new EventManager(c.getApplicationContext());}return instance;}
 public void setEnabled(boolean v){enabled=v;}public boolean isEnabled(){return enabled;}public void setListener(Listener v){listener=v;}public long getDroppedCount(){return dropped.get();}
 public void log(String m,String s,String msg){if(!enabled||m==null||m.trim().isEmpty()||s==null||msg==null)return;enqueue(m.trim(),s.trim().isEmpty()?"General":s.trim(),msg.trim(),false);}
 public void logLifecycle(String s,String msg){if(s!=null&&msg!=null)enqueue("Events",s,msg,true);}
 private void enqueue(String m,String s,String msg,boolean lifecycle){if(msg.isEmpty())return;try{writer.execute(()->{if(!lifecycle&&!enabled)return;try{database.insert(System.currentTimeMillis(),m,s,msg);main.post(()->{Listener l=listener;if(enabled&&l!=null)l.onEventsChanged();});}catch(RuntimeException ignored){}});}catch(RejectedExecutionException e){dropped.incrementAndGet();}}
 public List<EventRecord> read(List<String> m,int limit,long before){return enabled?database.read(m,limit,before):Collections.emptyList();}
 public List<EventRecord> readAll(List<String> m){return enabled?database.readAll(m):Collections.emptyList();}
 public List<String> getModules(){return enabled?database.getModules():Collections.emptyList();}
 public int count(){return enabled?database.count():0;}
 public void clear(){if(!enabled)return;try{writer.execute(()->{if(enabled)database.clear();main.post(()->{Listener l=listener;if(enabled&&l!=null)l.onEventsChanged();});});}catch(RejectedExecutionException e){dropped.incrementAndGet();}}
}