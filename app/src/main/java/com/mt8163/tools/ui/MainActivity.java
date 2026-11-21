package com.mt8163.tools.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;

import com.mt8163.tools.MainApplication;
import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;
import com.mt8163.tools.core.ModuleRegistry;
import com.mt8163.tools.core.PreferencesManager;
import com.mt8163.tools.modules.voltage.VoltageModuleView;
import com.mt8163.tools.modules.voltage.VoltageSettingsStore;

import java.util.List;

public final class MainActivity extends Activity {
    private ModuleRegistry registry;
    private PreferencesManager prefs;
    private List<ModuleContract> modules;
    private SidebarAdapter adapter;
    private FrameLayout header;
    private FrameLayout content;
    private View overlay;
    private int selected;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        MainApplication app = (MainApplication) getApplication();
        registry = app.getModuleRegistry();
        prefs = app.getPreferencesManager();
        modules = registry.getModules();

        ListView list = findViewById(R.id.sidebar_list);
        header = findViewById(R.id.header_container);
        content = findViewById(R.id.content_container);
        overlay = findViewById(R.id.disabled_overlay);

        adapter = new SidebarAdapter(this, modules);
        list.setAdapter(adapter);
        selected = modules.size() - 1;
        list.setOnItemClickListener((parent, view, position, id) -> showModule(position));
        showModule(selected);
    }

    private void showModule(int position) {
        if (position < 0 || position >= modules.size()) {
            return;
        }

        selected = position;
        ModuleContract module = modules.get(position);
        adapter.setSelectedPosition(position);

        header.removeAllViews();
        ModuleHeaderView headerView = new ModuleHeaderView(this);
        headerView.bind(module, enabled -> {
            registry.saveState(module, prefs);
            if ("voltage".equals(module.getId())) {
                VoltageSettingsStore.publish(this);
            }
            updateState();
            adapter.notifyDataSetChanged();
        });
        header.addView(headerView);

        content.removeAllViews();
        View moduleView = LayoutInflater.from(this).inflate(module.getLayoutRes(), content, false);
        content.addView(moduleView);
        if ("voltage".equals(module.getId())) {
            VoltageModuleView.bind(moduleView, this);
        }
        updateState();
    }

    private void updateState() {
        boolean enabled = modules.get(selected).isEnabled();
        setTreeEnabled(content, enabled);
        content.setAlpha(enabled ? 1.0f : 0.32f);
        overlay.setVisibility(enabled ? View.GONE : View.VISIBLE);
    }

    private void setTreeEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setTreeEnabled(group.getChildAt(i), enabled);
            }
        }
    }
}
