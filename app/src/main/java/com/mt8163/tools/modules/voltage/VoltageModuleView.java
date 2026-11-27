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
        SeekBar voltageOffsetSeekBar = root.findViewById(R.id.voltage_offset);
        TextView voltageOffsetValue = root.findViewById(R.id.voltage_offset_value);
        Button voltageResetButton = root.findViewById(R.id.voltage_reset);

        int seekBarProgress = progressFromOffset(VoltageSettingsStore.getOffset(context));
        voltageOffsetSeekBar.setProgress(seekBarProgress);
        voltageOffsetValue.setText(formatVoltageOffset(context, (seekBarProgress - 20) / 10.0f));

        voltageOffsetSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                voltageOffsetValue.setText(formatVoltageOffset(context, (value - 20) / 10.0f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (!saveAndPublishVoltageOffset(context, seekBar.getProgress())) {
                    restoreSavedOffset(context, seekBar, voltageOffsetValue);
                }
            }
        });

        voltageResetButton.setOnClickListener(view -> {
            voltageOffsetSeekBar.setProgress(20);
            voltageOffsetValue.setText(formatVoltageOffset(context, 0.0f));
            if (!saveAndPublishVoltageOffset(context, 20)) {
                restoreSavedOffset(context, voltageOffsetSeekBar, voltageOffsetValue);
            }
        });
    }

    private static boolean saveAndPublishVoltageOffset(Context context, int seekBarProgress) {
        float voltageOffset = (Math.max(0, Math.min(40, seekBarProgress)) - 20) / 10.0f;
        float previousVoltageOffset = VoltageSettingsStore.getOffset(context);
        if (!VoltageSettingsStore.saveOffset(context, voltageOffset)) {
            return false;
        }

        VoltageSettingsStore.publish(context);
        if (Float.compare(previousVoltageOffset, voltageOffset) != 0) {
            EventLogger.log(context, "Voltage", "settingsChanged",
                    context.getString(R.string.voltage_offset_changed,
                            previousVoltageOffset, voltageOffset));
        }
        return true;
    }

    private static void restoreSavedOffset(Context context, SeekBar seekBar, TextView valueView) {
        float savedOffset = VoltageSettingsStore.getOffset(context);
        seekBar.setProgress(progressFromOffset(savedOffset));
        valueView.setText(formatVoltageOffset(context, savedOffset));
    }

    private static int progressFromOffset(float offset) {
        return Math.max(0, Math.min(40, Math.round(offset * 10.0f) + 20));
    }

    private static String formatVoltageOffset(Context context, float voltageOffset) {
        return String.format(Locale.US, context.getString(R.string.voltage_offset_format), voltageOffset);
    }
}
