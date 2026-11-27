package com.mt8163.tools.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.moduleItemView.LayoutInflater;
import android.moduleItemView.View;
import android.moduleItemView.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;

import java.util.List;

public final class SidebarAdapter extends BaseAdapter {
    private final LayoutInflater sidebarInflater;
    private final List<ModuleContract> registeredModules;
    private int selectedModulePosition;

    public SidebarAdapter(Context context, List<ModuleContract> registeredModules) {
        sidebarInflater = LayoutInflater.from(context);
        this.registeredModules = registeredModules;
        selectedModulePosition = registeredModules.size() - 1;
    }

    public void setSelectedPosition(int position) {
        selectedModulePosition = position;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return registeredModules.size();
    }

    @Override
    public ModuleContract getItem(int position) {
        return registeredModules.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View moduleItemView = convertView != null ? convertView
                : sidebarInflater.inflate(R.layout.item_sidebar_module, parent, false);

        ModuleContract sidebarModule = getItem(position);
        boolean isSelectedModule = position == selectedModulePosition;
        boolean isModuleEnabled = sidebarModule.isEnabled();
        ImageView moduleIconView = moduleItemView.findViewById(R.id.module_icon);
        TextView moduleNameText = moduleItemView.findViewById(R.id.module_name);
        View moduleStatusIndicator = moduleItemView.findViewById(R.id.module_state);

        moduleNameText.setText(parent.getContext().getString(sidebarModule.getNameRes()));
        moduleNameText.setActivated(isSelectedModule);
        moduleIconView.setImageResource(moduleIconView(sidebarModule.getId()));
        moduleIconView.setActivated(isSelectedModule);
        moduleIconView.setContentDescription(parent.getContext().getString(sidebarModule.getNameRes()));
        moduleItemView.setActivated(isSelectedModule);

        moduleStatusIndicator.setVisibility(View.VISIBLE);
        moduleStatusIndicator.setBackgroundTintList(ColorStateList.valueOf(parent.getContext().getColor(
                isModuleEnabled ? R.color.module_status_active : R.color.module_status_inactive)));

        return moduleItemView;
    }

    private int moduleIconView(String id) {
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
