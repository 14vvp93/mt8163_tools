package com.mt8163.tools.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;

public final class ModuleHeaderView extends LinearLayout {
    private final TextView title;
    private final TextView status;
    private final Switch toggle;

    public ModuleHeaderView(Context context) {
        this(context, null);
    }

    public ModuleHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.view_module_header, this, true);
        title = findViewById(R.id.header_title);
        status = findViewById(R.id.header_status);
        toggle = findViewById(R.id.header_switch);
    }

    public void bind(ModuleContract module, OnCheckedChangeListener listener) {
        title.setText(module.getName());
        updateStatus(module.isEnabled());

        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(module.isEnabled());
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            module.setEnabled(enabled);
            updateStatus(enabled);
            if (listener != null) listener.onCheckedChanged(enabled);
        });
    }

    private void updateStatus(boolean enabled) {
        status.setText(enabled ? R.string.module_enabled : R.string.module_disabled);
        status.setTextColor(getContext().getColor(enabled
                ? R.color.module_status_active : R.color.text_muted));
    }

    public interface OnCheckedChangeListener {
        void onCheckedChanged(boolean enabled);
    }
}
