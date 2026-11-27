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
        moduleListView.setOnItemClickListener(
                (parent, view, position, id) -> showModule(position));
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
                EventManager eventsManager = EventManager.get(this);
                eventsManager.setEnabled(enabled);
                eventsManager.logLifecycle(enabled ? "moduleEnabled" : "moduleDisabled",
                        enabled ? "Events module enabled" : "Events module disabled");
            }

            if ("voltage".equals(selectedModule.getId())) {
                EventLogger.log(this, "Voltage",
                        enabled ? "moduleEnabled" : "moduleDisabled",
                        enabled ? "Voltage module enabled" : "Voltage module disabled");
                VoltageSettingsStore.publish(this);
            }

            if ("media".equals(selectedModule.getId())) {
                MediaSettingsStore.publish(this);
            }

            if ("protection".equals(selectedModule.getId())) {
                EventLogger.log(this, "Protection",
                        enabled ? "moduleEnabled" : "moduleDisabled",
                        enabled ? "Protection module enabled" : "Protection module disabled");
                ProtectionSettingsStore.publish(this);
            }

            sidebarAdapter.notifyDataSetChanged();
            if ("events".equals(selectedModule.getId())) showModule(selectedModulePosition);
            else updateSelectedModuleVisibility();
        });
        moduleHeaderContainer.addView(moduleHeaderView);

        EventsModuleView.destroy();
        moduleContentContainer.removeAllViews();
        if ("events".equals(selectedModule.getId())) {
            if (selectedModule.isEnabled()) {
                moduleContentContainer.addView(EventsModuleView.create(this, registeredModules));
            }
        } else {
            View moduleView = LayoutInflater.from(this)
                    .inflate(selectedModule.getLayoutRes(), moduleContentContainer, false);
            moduleContentContainer.addView(moduleView);
            if ("voltage".equals(selectedModule.getId())) {
                VoltageModuleView.bind(moduleView, this);
            } else if ("media".equals(selectedModule.getId())) {
                new MediaModuleView(moduleView, this);
            } else if ("protection".equals(selectedModule.getId())) {
                new ProtectionModuleView(moduleView, this);
            }
        }
        updateSelectedModuleVisibility();
    }

    private void updateSelectedModuleVisibility() {
        boolean moduleEnabled = registeredModules.get(selectedModulePosition).isEnabled();
        moduleContentContainer.setVisibility(moduleEnabled ? View.VISIBLE : View.GONE);
        disabledModuleOverlay.setVisibility(moduleEnabled ? View.GONE : View.VISIBLE);
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
