package com.mt8163.tools.ui;
import android.content.Context;import android.view.LayoutInflater;import android.view.View;import android.view.ViewGroup;import android.widget.BaseAdapter;import android.widget.TextView;import com.mt8163.tools.R;import com.mt8163.tools.core.ModuleContract;import java.util.List;
public final class SidebarAdapter extends BaseAdapter{
 private final LayoutInflater inflater;private final List<ModuleContract> modules;private int selected;
 public SidebarAdapter(Context c,List<ModuleContract> m){inflater=LayoutInflater.from(c);modules=m;selected=m.size()-1;}public void setSelectedPosition(int p){selected=p;notifyDataSetChanged();}
 public int getCount(){return modules.size();}public ModuleContract getItem(int p){return modules.get(p);}public long getItemId(int p){return p;}
 public View getView(int p,View old,ViewGroup parent){View v=old!=null?old:inflater.inflate(R.layout.item_sidebar_module,parent,false);ModuleContract m=getItem(p);((TextView)v.findViewById(R.id.module_name)).setText(m.getName());((TextView)v.findViewById(R.id.module_icon)).setText(icon(m.getId()));v.findViewById(R.id.module_state).setVisibility(m.isEnabled()?View.VISIBLE:View.INVISIBLE);v.setBackgroundResource(p==selected?R.drawable.sidebar_item_selected:R.drawable.sidebar_item_background);return v;}
 private String icon(String id){switch(id){case "media":return "♫";case "protection":return "◇";case "voltage":return "ϟ";case "autorun":return "↗";case "events":return "≋";default:return "•";}}
}