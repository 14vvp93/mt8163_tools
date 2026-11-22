package com.mt8163.tools.modules.media;

import android.content.Context;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import com.mt8163.tools.R;

public final class MediaModuleView {
    private final TextView sourcePackage;
    private final TextView playbackState;
    private final TextView rawState;
    private final TextView foregroundState;
    private final Switch restoreMedia;
    private final Switch restoreBluetooth;
    private final Context context;
    private String currentPackage;
    private String currentState;

    public MediaModuleView(View root, Context context) {
        this.context = context.getApplicationContext();
        sourcePackage = root.findViewById(R.id.media_source_package);
        playbackState = root.findViewById(R.id.media_playback_state);
        rawState = root.findViewById(R.id.media_raw_state);
        foregroundState = root.findViewById(R.id.media_foreground_state);
        restoreMedia = root.findViewById(R.id.media_restore_background_media);
        restoreBluetooth = root.findViewById(R.id.media_restore_background_bluetooth);

        restoreMedia.setChecked(MediaSettingsStore.isRestoreBackgroundMedia(context));
        restoreBluetooth.setChecked(MediaSettingsStore.isRestoreBackgroundBluetooth(context));

        restoreMedia.setOnCheckedChangeListener((button, checked) ->
                MediaSettingsStore.setRestoreBackgroundMedia(this.context, checked));
        restoreBluetooth.setOnCheckedChangeListener((button, checked) ->
                MediaSettingsStore.setRestoreBackgroundBluetooth(this.context, checked));

        update(null, null, false);
    }

    public void update(String packageName, String state, boolean isForeground) {
        if (packageName != null) currentPackage = packageName.trim();
        if (state != null) currentState = state.trim();

        String source = currentPackage == null || currentPackage.isEmpty()
                ? "Unknown" : currentPackage;
        String raw = currentState == null ? "" : currentState;

        sourcePackage.setText(source);
        rawState.setText(raw.isEmpty() ? "Raw state: unavailable" : "Raw state: " + raw);
        foregroundState.setText(isForeground ? "FOREGROUND / FOCUSED" : "NOT FOREGROUND");

        if ("playing".equalsIgnoreCase(raw)) {
            playbackState.setText("PLAYING");
        } else if ("paused".equalsIgnoreCase(raw)) {
            playbackState.setText("PAUSED");
        } else {
            playbackState.setText("UNKNOWN");
        }
    }
}
