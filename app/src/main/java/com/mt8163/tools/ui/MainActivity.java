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
import com.mt8163.tools.core.ModuleContract;
import com.mt8163.tools.core.ModuleRegistry;
import com.mt8163.tools.core.PreferencesManager;
import com.mt8163.tools.modules.ModuleUiContract;

import java.util.List;

public final class MainActivity extends Activity {
    private ModuleRegistry moduleRegistry;
    private PreferencesManager preferencesManager;
    private List<ModuleContract> registeredModules;
    private SidebarAdapter sidebarAdapter;
    private FrameLayout moduleHeaderContainer;
    private FrameLayout moduleContentContainer;
    private View disabledModuleOverlay;
    private ModuleUiContract currentModuleUi;
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

        if (currentModuleUi != null) currentModuleUi.onContentRemoved();
        currentModuleUi = null;
        selectedModulePosition = position;
        ModuleContract selectedModule = registeredModules.get(position);
        if (selectedModule instanceof ModuleUiContract) {
            currentModuleUi = (ModuleUiContract) selectedModule;
        }

        sidebarAdapter.setSelectedPosition(position);
        moduleHeaderContainer.removeAllViews();

        View moduleHeaderView = LayoutInflater.from(this)
                .inflate(R.layout.view_module_header, moduleHeaderContainer, false);
        ModuleHeaderView.bind(moduleHeaderView, selectedModule, enabled -> {
            moduleRegistry.saveState(selectedModule, preferencesManager);
            if (selectedModule instanceof ModuleUiContract) {
                ModuleUiContract moduleUi = (ModuleUiContract) selectedModule;
                moduleUi.onEnabledChanged(this, enabled);
                if (moduleUi.recreateContentOnEnabledChange()) {
                    sidebarAdapter.notifyDataSetChanged();
                    showModule(selectedModulePosition);
                    return;
                }
            }
            sidebarAdapter.notifyDataSetChanged();
            updateSelectedModuleVisibility();
        });
        moduleHeaderContainer.addView(moduleHeaderView);

        moduleContentContainer.removeAllViews();
        View moduleView = currentModuleUi == null
                ? LayoutInflater.from(this).inflate(
                selectedModule.getLayoutRes(), moduleContentContainer, false)
                : currentModuleUi.createContentView(
                this, moduleContentContainer, registeredModules);
        if (moduleView != null) moduleContentContainer.addView(moduleView);
        updateSelectedModuleVisibility();
    }

    private void updateSelectedModuleVisibility() {
        boolean moduleEnabled = registeredModules.get(selectedModulePosition).isEnabled();
        moduleContentContainer.setVisibility(moduleEnabled ? View.VISIBLE : View.GONE);
        disabledModuleOverlay.setVisibility(moduleEnabled ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        if (currentModuleUi != null) currentModuleUi.onContentRemoved();
        currentModuleUi = null;
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        for (ModuleContract module : registeredModules) {
            if (module instanceof ModuleUiContract
                    && ((ModuleUiContract) module).onActivityResult(
                    requestCode, resultCode, data)) return;
        }
    }
}
