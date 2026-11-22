package com.mt8163.tools.modules.media;

import android.view.View;
import android.widget.TextView;

import com.mt8163.tools.R;

public final class MediaModuleView {
    private final TextView sourcePackage;
    private final TextView playbackState;
    private final TextView rawState;
    private String currentPackage;
    private String currentState;

    public MediaModuleView(View root) {
        sourcePackage = root.findViewById(R.id.media_source_package);
        playbackState = root.findViewById(R.id.media_playback_state);
        rawState = root.findViewById(R.id.media_raw_state);
        update(null, null);
    }

    public void update(String packageName, String state) {
        if (packageName != null) currentPackage = packageName.trim();
        if (state != null) currentState = state.trim();

        String source = currentPackage == null || currentPackage.isEmpty()
                ? "Unknown" : currentPackage;
        String raw = currentState == null ? "" : currentState;

        sourcePackage.setText(source);
        rawState.setText(raw.isEmpty() ? "Raw state: unavailable" : "Raw state: " + raw);

        if ("start".equalsIgnoreCase(raw) || "write".equalsIgnoreCase(raw)) {
            playbackState.setText("PLAYING");
        } else if ("paused".equalsIgnoreCase(raw)) {
            playbackState.setText("PAUSED");
        } else if ("stop".equalsIgnoreCase(raw) || "stopped".equalsIgnoreCase(raw)) {
            playbackState.setText("STOPPED");
        } else {
            playbackState.setText("UNKNOWN");
        }
    }
}
