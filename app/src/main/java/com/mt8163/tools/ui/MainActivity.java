package com.mt8163.tools.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ListView;

import com.mt8163.tools.MainApplication;
import com.mt8163.tools.R;
import com.mt8163.tools.core.EventLogger;
import com.mt8163.tools.core.ModuleContract;
import com.mt8163.tools.core.ModuleRegistry;
import com.mt8163.tools.core.PreferencesManager;
import com.mt8163.tools.modules.events.EventManager;
import com.mt8163.tools.modules.events.EventsModuleView;
import com.mt8163.tools.modules.media.MediaModuleView;
import com.mt8163.tools.modules.media.MediaSettingsStore;
import com.mt8163.tools.modules.protection.ProtectionModuleView;
import com.mt8163.tools.modules.protection.ProtectionSettingsStore;
import com.mt8163.tools.modules.voltage.VoltageModuleView;
import com.mt8163.tools.modules.voltage.VoltageSettingsStore;

import java.util.List;

public final class MainActivity extends Activity {
    private ModuleRegistry registry;
    private PreferencesManager preferences;
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
        preferences = app.getPreferencesManager();
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
        if (position < 0 || position >= modules.size()) return;

        selected = position;
        ModuleContract module = modules.get(position);
        adapter.setSelectedPosition(position);
        header.removeAllViews();

        View headerView = LayoutInflater.from(this)
                .inflate(R.layout.view_module_header, header, false);
        ModuleHeaderView.bind(headerView, module, enabled -> {
            registry.saveState(module, preferences);

            if ("events".equals(module.getId())) {
                EventManager events = EventManager.get(this);
                events.setEnabled(enabled);
                events.logLifecycle(enabled ? "moduleEnabled" : "moduleDisabled",
                        enabled ? "Events module enabled" : "Events module disabled");
            }

            if ("voltage".equals(module.getId())) {
                EventLogger.log(this, "Voltage",
                        enabled ? "moduleEnabled" : "moduleDisabled",
                        enabled ? "Voltage module enabled" : "Voltage module disabled");
                VoltageSettingsStore.publish(this);
            }

            if ("media".equals(module.getId())) {
                MediaSettingsStore.publish(this);
            }

            if ("protection".equals(module.getId())) {
                ProtectionSettingsStore.publish(this);
            }

            adapter.notifyDataSetChanged();
            if ("events".equals(module.getId())) showModule(selected);
            else updateState();
        });
        header.addView(headerView);

        EventsModuleView.destroy();
        content.removeAllViews();
        if ("events".equals(module.getId())) {
            if (module.isEnabled()) content.addView(EventsModuleView.create(this, modules));
        } else {
            View view = LayoutInflater.from(this)
                    .inflate(module.getLayoutRes(), content, false);
            content.addView(view);
            if ("voltage".equals(module.getId())) {
                VoltageModuleView.bind(view, this);
            } else if ("media".equals(module.getId())) {
                new MediaModuleView(view, this);
            } else if ("protection".equals(module.getId())) {
                new ProtectionModuleView(view, this);
            }
        }
        updateState();
    }

    private void updateState() {
        boolean enabled = modules.get(selected).isEnabled();
        content.setVisibility(enabled ? View.VISIBLE : View.GONE);
        overlay.setVisibility(enabled ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        EventsModuleView.destroy();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        EventsModuleView.onActivityResult(requestCode, resultCode, data);
    }
}
