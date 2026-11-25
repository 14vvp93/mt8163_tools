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
        TextView title = root.findViewById(R.id.header_title);
        TextView status = root.findViewById(R.id.header_status);
        Switch toggle = root.findViewById(R.id.header_switch);

        title.setText(root.getContext().getString(module.getNameRes()));
        updateStatus(status, module.isEnabled());

        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(module.isEnabled());
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            module.setEnabled(enabled);
            updateStatus(status, enabled);
            if (listener != null) listener.onCheckedChanged(enabled);
        });
    }

    private static void updateStatus(TextView status, boolean enabled) {
        status.setText(enabled ? R.string.module_enabled : R.string.module_disabled);
        status.setTextColor(status.getContext().getColor(
                enabled ? R.color.module_status_active : R.color.neon_red));
    }

    public interface OnCheckedChangeListener {
        void onCheckedChanged(boolean enabled);
    }
}
