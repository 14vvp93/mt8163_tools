package com.mt8163.tools.ui;
import android.content.Context;import android.util.AttributeSet;import android.view.LayoutInflater;import android.widget.LinearLayout;import android.widget.Switch;import android.widget.TextView;import com.mt8163.tools.R;import com.mt8163.tools.core.ModuleContract;
public final class ModuleHeaderView extends LinearLayout{
 private final TextView title,status;private final Switch toggle;
 public ModuleHeaderView(Context c){this(c,null);}public ModuleHeaderView(Context c,AttributeSet a){super(c,a);LayoutInflater.from(c).inflate(R.layout.view_module_header,this,true);title=findViewById(R.id.header_title);status=findViewById(R.id.header_status);toggle=findViewById(R.id.header_switch);}
 public void bind(ModuleContract m,OnCheckedChangeListener l){title.setText(m.getName());status.setText(m.isEnabled()?R.string.module_enabled:R.string.module_disabled);toggle.setOnCheckedChangeListener(null);toggle.setChecked(m.isEnabled());toggle.setOnCheckedChangeListener((b,v)->{m.setEnabled(v);status.setText(v?R.string.module_enabled:R.string.module_disabled);if(l!=null)l.onCheckedChanged(v);});}
 public interface OnCheckedChangeListener{void onCheckedChanged(boolean enabled);}
}