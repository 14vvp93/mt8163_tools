package com.mt8163.tools.modules.voltage;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import com.mt8163.tools.R;
import com.mt8163.tools.core.EventLogger;

import java.util.Locale;

public final class VoltageModuleView {
    private VoltageModuleView() {
    }

    public static void bind(View root, Context context) {
        SeekBar offsetBar = root.findViewById(R.id.voltage_offset);
        TextView valueText = root.findViewById(R.id.voltage_offset_value);
        Button resetButton = root.findViewById(R.id.voltage_reset);

        int progress = Math.max(0, Math.min(40,
                Math.round(VoltageSettingsStore.getOffset(context) * 10.0f) + 20));
        offsetBar.setProgress(progress);
        valueText.setText(formatOffset((progress - 20) / 10.0f));

        offsetBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                valueText.setText(formatOffset((value - 20) / 10.0f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                saveAndPublish(context, seekBar.getProgress());
            }
        });

        resetButton.setOnClickListener(view -> {
            offsetBar.setProgress(20);
            valueText.setText(formatOffset(0.0f));
            saveAndPublish(context, 20);
        });
    }

    private static void saveAndPublish(Context context, int progress) {
        float offset = (Math.max(0, Math.min(40, progress)) - 20) / 10.0f;
        float previous = VoltageSettingsStore.getOffset(context);
        VoltageSettingsStore.saveOffset(context, offset);
        VoltageSettingsStore.publish(context);
        if (Float.compare(previous, offset) != 0) {
            EventLogger.log(context, "Voltage", "settingsChanged",
                    String.format(Locale.US, "Voltage offset: %+.1f V → %+.1f V",
                            previous, offset));
        }
    }

    private static String formatOffset(float offset) {
        return String.format(Locale.US, context.getString(R.string.voltage_offset_format), offset);
    }
}
