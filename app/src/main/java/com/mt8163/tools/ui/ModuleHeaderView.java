package com.mt8163.tools.ui;

import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;

public final class ModuleHeaderView {
    private ModuleHeaderView() {
    }

    public static void bind(View root, ModuleContract module,
                            OnCheckedChangeListener listener) {
        TextView moduleTitleText = root.findViewById(R.id.header_title);
        TextView moduleStatusText = root.findViewById(R.id.header_status);
        Switch moduleEnabledSwitch = root.findViewById(R.id.header_switch);

        moduleTitleText.setText(root.getContext().getString(module.getNameRes()));
        updateStatus(moduleStatusText, module.isEnabled());

        moduleEnabledSwitch.setOnCheckedChangeListener(null);
        moduleEnabledSwitch.setChecked(module.isEnabled());
        moduleEnabledSwitch.setOnCheckedChangeListener((button, enabled) -> {
            module.setEnabled(enabled);
            updateStatus(moduleStatusText, enabled);
            if (listener != null) listener.onCheckedChanged(enabled);
        });
    }

    private static void updateStatus(TextView moduleStatusText, boolean enabled) {
        moduleStatusText.setText(enabled ? R.string.module_enabled : R.string.module_disabled);
        moduleStatusText.setTextColor(moduleStatusText.getContext().getColor(
                enabled ? R.color.module_status_active : R.color.module_status_inactive));
    }

    public interface OnCheckedChangeListener {
        void onCheckedChanged(boolean enabled);
    }
}
