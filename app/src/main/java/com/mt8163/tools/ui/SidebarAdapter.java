package com.mt8163.tools.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;

import java.util.List;

public final class SidebarAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<ModuleContract> modules;
    private int selected;

    public SidebarAdapter(Context context, List<ModuleContract> modules) {
        inflater = LayoutInflater.from(context);
        this.modules = modules;
        selected = modules.size() - 1;
    }

    public void setSelectedPosition(int position) {
        selected = position;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return modules.size();
    }

    @Override
    public ModuleContract getItem(int position) {
        return modules.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView != null ? convertView
                : inflater.inflate(R.layout.item_sidebar_module, parent, false);

        ModuleContract module = getItem(position);
        boolean isSelected = position == selected;
        boolean enabled = module.isEnabled();
        ImageView icon = view.findViewById(R.id.module_icon);
        TextView name = view.findViewById(R.id.module_name);
        View state = view.findViewById(R.id.module_state);

        name.setText(parent.getContext().getString(module.getNameRes()));
        name.setActivated(isSelected);
        icon.setImageResource(icon(module.getId()));
        icon.setActivated(isSelected);
        icon.setContentDescription(parent.getContext().getString(module.getNameRes()));
        view.setActivated(isSelected);

        state.setVisibility(View.VISIBLE);
        state.setBackgroundTintList(ColorStateList.valueOf(parent.getContext().getColor(
                enabled ? R.color.module_status_active : R.color.neon_red)));

        return view;
    }

    private int icon(String id) {
        switch (id) {
            case "media": return R.drawable.ic_module_media;
            case "protection": return R.drawable.ic_module_protection;
            case "voltage": return R.drawable.ic_module_voltage;
            case "autorun": return R.drawable.ic_module_autorun;
            case "events": return R.drawable.ic_module_events;
            default: return R.drawable.ic_module_default;
        }
    }
}
