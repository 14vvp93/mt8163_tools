package com.mt8163.tools.modules.media;

import android.content.Context;
import android.view.View;
import android.widget.Switch;

import com.mt8163.tools.R;

public final class MediaModuleView {
    private final Context context;
    private final Switch restoreMedia;
    private final Switch restoreBluetooth;

    public MediaModuleView(View root, Context context) {
        this.context = context.getApplicationContext();
        restoreMedia = root.findViewById(R.id.media_restore_background_media);
        restoreBluetooth = root.findViewById(R.id.media_restore_background_bluetooth);

        restoreMedia.setChecked(MediaSettingsStore.isRestoreBackgroundMedia(this.context));
        restoreBluetooth.setChecked(
                MediaSettingsStore.isRestoreBackgroundBluetooth(this.context));

        restoreMedia.setOnCheckedChangeListener((button, checked) ->
                MediaSettingsStore.setRestoreBackgroundMedia(this.context, checked));
        restoreBluetooth.setOnCheckedChangeListener((button, checked) ->
                MediaSettingsStore.setRestoreBackgroundBluetooth(this.context, checked));
    }
}
