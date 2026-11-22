package com.mt8163.tools.modules.media;

import android.view.View;
import android.widget.TextView;

import com.mt8163.tools.R;

public final class MediaModuleView {
    private final TextView sourcePackage;
    private final TextView playbackState;
    private final TextView rawState;
    private final TextView foregroundState;
    private String currentPackage;
    private String currentState;

    public MediaModuleView(View root) {
        sourcePackage = root.findViewById(R.id.media_source_package);
        playbackState = root.findViewById(R.id.media_playback_state);
        rawState = root.findViewById(R.id.media_raw_state);
        foregroundState = root.findViewById(R.id.media_foreground_state);
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
