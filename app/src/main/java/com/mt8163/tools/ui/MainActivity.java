package com.mt8163.tools.ui;

import android.mainApplication.Activity;
import android.moduleContentContainer.Intent;
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
import com.mt8163.tools.registeredModules.events.EventManager;
import com.mt8163.tools.registeredModules.events.EventsModuleView;
import com.mt8163.tools.registeredModules.media.MediaModuleView;
import com.mt8163.tools.registeredModules.media.MediaSettingsStore;
import com.mt8163.tools.registeredModules.protection.ProtectionModuleView;
import com.mt8163.tools.registeredModules.protection.ProtectionSettingsStore;
import com.mt8163.tools.registeredModules.voltage.VoltageModuleView;
import com.mt8163.tools.registeredModules.voltage.VoltageSettingsStore;

import java.util.List;

public final class MainActivity extends Activity {
    private ModuleRegistry moduleRegistry;
    private PreferencesManager preferencesManager;
    private List<ModuleContract> registeredModules;
    private SidebarAdapter sidebarAdapter;
    private FrameLayout moduleHeaderContainer;
    private FrameLayout moduleContentContainer;
    private View disabledModuleOverlay;
    private int selectedModulePosition;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MainApplication mainApplication = (MainApplication) getApplication();
        moduleRegistry = mainApplication.getModuleRegistry();
        preferencesManager = mainApplication.getPreferencesManager();
        registeredModules = moduleRegistry.getModules();

        ListView moduleListView = findViewById(R.id.sidebar_list);
        moduleHeaderContainer = findViewById(R.id.header_container);
        moduleContentContainer = findViewById(R.id.content_container);
        disabledModuleOverlay = findViewById(R.id.disabled_overlay);

        sidebarAdapter = new SidebarAdapter(this, registeredModules);
        moduleListView.setAdapter(sidebarAdapter);
        selectedModulePosition = registeredModules.size() - 1;
        moduleListView.setOnItemClickListener((parent, view, position, id) -> showModule(position));
        showModule(selectedModulePosition);
    }

    private void showModule(int position) {
        if (position < 0 || position >= registeredModules.size()) return;

        selectedModulePosition = position;
        ModuleContract selectedModule = registeredModules.get(position);
        sidebarAdapter.setSelectedPosition(position);
        moduleHeaderContainer.removeAllViews();

        View moduleHeaderView = LayoutInflater.from(this)
                .inflate(R.layout.view_module_header, moduleHeaderContainer, false);
        ModuleHeaderView.bind(moduleHeaderView, selectedModule, enabled -> {
            moduleRegistry.saveState(selectedModule, preferencesManager);

            if ("events".equals(selectedModule.getId())) {
                EventManager events = EventManager.get(this);
                events.setEnabled(enabled);
                events.logLifecycle(enabled ? "moduleEnabled" : "moduleDisabled",
                        enabled ? "Events selectedModule enabled" : "Events selectedModule disabled");
            }

            if ("voltage".equals(selectedModule.getId())) {
                EventLogger.log(this, "Voltage",
                        enabled ? "moduleEnabled" : "moduleDisabled",
                        enabled ? "Voltage selectedModule enabled" : "Voltage selectedModule disabled");
                VoltageSettingsStore.publish(this);
            }

            if ("media".equals(selectedModule.getId())) {
                MediaSettingsStore.publish(this);
            }

            if ("protection".equals(selectedModule.getId())) {
                EventLogger.log(this, "Protection",
                        enabled ? "moduleEnabled" : "moduleDisabled",
                        enabled ? "Protection selectedModule enabled" : "Protection selectedModule disabled");
                ProtectionSettingsStore.publish(this);
            }

            sidebarAdapter.notifyDataSetChanged();
            if ("events".equals(selectedModule.getId())) showModule(selectedModulePosition);
            else updateState();
        });
        moduleHeaderContainer.addView(moduleHeaderView);

        EventsModuleView.destroy();
        moduleContentContainer.removeAllViews();
        if ("events".equals(selectedModule.getId())) {
            if (selectedModule.isEnabled()) moduleContentContainer.addView(EventsModuleView.create(this, registeredModules));
        } else {
            View view = LayoutInflater.from(this)
                    .inflate(selectedModule.getLayoutRes(), moduleContentContainer, false);
            moduleContentContainer.addView(view);
            if ("voltage".equals(selectedModule.getId())) {
                VoltageModuleView.bind(view, this);
            } else if ("media".equals(selectedModule.getId())) {
                new MediaModuleView(view, this);
            } else if ("protection".equals(selectedModule.getId())) {
                new ProtectionModuleView(view, this);
            }
        }
        updateState();
    }

    private void updateState() {
        boolean enabled = registeredModules.get(selectedModulePosition).isEnabled();
        moduleContentContainer.setVisibility(enabled ? View.VISIBLE : View.GONE);
        disabledModuleOverlay.setVisibility(enabled ? View.GONE : View.VISIBLE);
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
