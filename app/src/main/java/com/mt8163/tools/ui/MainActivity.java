package com.mt8163.tools.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Toast;

import com.mt8163.tools.MainApplication;
import com.mt8163.tools.R;
import com.mt8163.tools.core.EventLogger;
import com.mt8163.tools.core.ModuleContract;
import com.mt8163.tools.core.ModuleRegistry;
import com.mt8163.tools.core.PreferencesManager;
import com.mt8163.tools.core.design.DesignRuntime;
import com.mt8163.tools.core.design.DesignStore;
import com.mt8163.tools.modules.design.DesignModuleView;
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
    private static final int REQUEST_DESIGN_STORAGE = 4101;

    private ModuleRegistry registry;
    private PreferencesManager preferences;
    private DesignStore designStore;
    private Runnable designListener;
    private List<ModuleContract> modules;
    private SidebarAdapter adapter;
    private FrameLayout header;
    private FrameLayout content;
    private View overlay;
    private View appRoot;
    private int selected;
    private DesignModuleView designPanel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        MainApplication app = (MainApplication) getApplication();
        registry = app.getModuleRegistry();
        preferences = app.getPreferencesManager();
        designStore = app.getDesignStore();
        modules = registry.getModules();

        ListView list = findViewById(R.id.sidebar_list);
        header = findViewById(R.id.header_container);
        content = findViewById(R.id.content_container);
        overlay = findViewById(R.id.disabled_overlay);
        appRoot = findViewById(android.R.id.content);
        designPanel = new DesignModuleView(appRoot, designStore);

        adapter = new SidebarAdapter(this, modules);
        list.setAdapter(adapter);
        selected = modules.size() - 1;
        list.setOnItemClickListener((parent, view, position, id) -> showModule(position));

        designListener = () -> {
            DesignRuntime.applyTree(appRoot, designStore);
            int appColor = designStore.getColor("appColor", getColor(R.color.app_background));
            appRoot.setBackgroundColor(appColor);
            View sidebar = findViewById(R.id.sidebar_container);
            if (sidebar != null) sidebar.setBackgroundColor(designStore.getColor(
                    "sidebarColor", getColor(R.color.sidebar_background)));
            if (header.getChildCount() > 0) {
                header.getChildAt(0).setBackgroundColor(designStore.getColor(
                        "headerColor", getColor(R.color.header_background)));
            }
            getWindow().setStatusBarColor(appColor);
            getWindow().setNavigationBarColor(appColor);
            adapter.notifyDataSetChanged();
        };
        designStore.addListener(designListener);
        DesignRuntime.attach(appRoot, designStore, "global");
        designListener.run();
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
        View designButton = headerView.findViewById(R.id.header_design_button);
        designButton.setOnClickListener(v -> openDesignLab());
        header.addView(headerView);
        DesignRuntime.attach(headerView, designStore, "shell");

        EventsModuleView.destroy();
        content.removeAllViews();
        if ("events".equals(module.getId())) {
            if (module.isEnabled()) {
                View eventsView = EventsModuleView.create(this, modules);
                content.addView(eventsView);
                DesignRuntime.attach(eventsView, designStore, module.getId());
            }
        } else {
            View view = LayoutInflater.from(this)
                    .inflate(module.getLayoutRes(), content, false);
            content.addView(view);
            if ("design".equals(module.getId())) {
                DesignRuntime.attach(view, designStore, module.getId());
                view.findViewById(R.id.design_controls).setVisibility(View.GONE);
                view.findViewById(R.id.design_reset_all).setVisibility(View.GONE);
            } else {
                if ("voltage".equals(module.getId())) {
                    VoltageModuleView.bind(view, this);
                } else if ("media".equals(module.getId())) {
                    new MediaModuleView(view, this);
                } else if ("protection".equals(module.getId())) {
                    new ProtectionModuleView(view, this);
                }
                DesignRuntime.attach(view, designStore, module.getId());
            }
        }
        updateState();
    }

    private void openDesignLab() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            if (designStore.syncExternalFile()) {
                designPanel.show();
            } else {
                Toast.makeText(this, "Cannot read or write design_parameters.json",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_DESIGN_STORAGE);
    }

    private void requestDesignStorageAccess() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            if (!designStore.syncExternalFile()) {
                Toast.makeText(this, "Cannot read or write design_parameters.json",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_DESIGN_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_DESIGN_STORAGE) return;

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (designStore.syncExternalFile()) {
                Toast.makeText(this, "Design parameters saved to "
                        + designStore.getExternalFilePath(), Toast.LENGTH_LONG).show();
                showModule(selected);
            } else {
                Toast.makeText(this, "Cannot read or write design_parameters.json",
                        Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Storage permission is required to export design parameters",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void updateState() {
        boolean enabled = modules.get(selected).isEnabled();
        content.setVisibility(enabled ? View.VISIBLE : View.GONE);
        overlay.setVisibility(enabled ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        if (designStore != null) designStore.removeListener(designListener);
        EventsModuleView.destroy();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        EventsModuleView.onActivityResult(requestCode, resultCode, data);
    }
}
