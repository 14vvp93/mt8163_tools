package com.mt8163.tools.modules.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import com.mt8163.tools.R;

public final class MediaModuleView {
    private final Context context;
    private final Switch restoreMedia;
    private final Switch restoreBluetooth;
    private final TextView snapshotSource;
    private final TextView snapshotPackage;
    private final TextView snapshotPlayback;
    private final TextView snapshotWindow;
    private boolean receiverRegistered;

    private final BroadcastReceiver snapshotReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshSnapshot();
        }
    };

    public MediaModuleView(View root, Context context) {
        this.context = context.getApplicationContext();
        restoreMedia = root.findViewById(R.id.media_restore_background_media);
        restoreBluetooth = root.findViewById(R.id.media_restore_background_bluetooth);
        snapshotSource = root.findViewById(R.id.media_snapshot_source);
        snapshotPackage = root.findViewById(R.id.media_snapshot_package);
        snapshotPlayback = root.findViewById(R.id.media_snapshot_playback);
        snapshotWindow = root.findViewById(R.id.media_snapshot_window);

        restoreMedia.setChecked(MediaSettingsStore.isRestoreBackgroundMedia(this.context));
        restoreBluetooth.setChecked(
                MediaSettingsStore.isRestoreBackgroundBluetooth(this.context));

        restoreMedia.setOnCheckedChangeListener((button, checked) ->
                MediaSettingsStore.setRestoreBackgroundMedia(this.context, checked));
        restoreBluetooth.setOnCheckedChangeListener((button, checked) ->
                MediaSettingsStore.setRestoreBackgroundBluetooth(this.context, checked));

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                registerSnapshotReceiver();
                refreshSnapshot();
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                unregisterSnapshotReceiver();
            }
        });

        if (root.isAttachedToWindow()) registerSnapshotReceiver();
        refreshSnapshot();
    }

    private void refreshSnapshot() {
        String packageName = MediaSettingsStore.getSourcePackage(context);
        boolean hasSnapshot = packageName != null && !packageName.trim().isEmpty();

        snapshotSource.setText(getSnapshotSourceLabel(hasSnapshot));
        snapshotPackage.setText(hasSnapshot
                ? packageName : context.getString(R.string.media_unknown));
        snapshotPlayback.setText(hasSnapshot
                ? displayPlayback(MediaSettingsStore.getPlaybackState(context))
                : context.getString(R.string.media_unknown));
        snapshotWindow.setText(hasSnapshot
                ? (MediaSettingsStore.isForeground(context)
                ? context.getString(R.string.media_foreground)
                : context.getString(R.string.media_background))
                : context.getString(R.string.media_unknown));
    }

    private int getSnapshotSourceLabel(boolean hasSnapshot) {
        if (!hasSnapshot) return R.string.media_snapshot_unknown_source;
        return MediaSettingsStore.isBluetoothSource(context)
                ? R.string.media_snapshot_bluetooth
                : R.string.media_snapshot_media_player;
    }

    private String displayPlayback(String value) {
        if (MediaSettingsStore.PLAYBACK_PLAYING.equals(value)) {
            return context.getString(R.string.media_playing);
        }
        if (MediaSettingsStore.PLAYBACK_PAUSED.equals(value)) {
            return context.getString(R.string.media_paused);
        }
        return context.getString(R.string.media_unknown);
    }

    private void registerSnapshotReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(MediaSettingsStore.ACTION_SNAPSHOT_UPDATED);
        context.registerReceiver(snapshotReceiver, filter);
        receiverRegistered = true;
    }

    private void unregisterSnapshotReceiver() {
        if (!receiverRegistered) return;
        try {
            context.unregisterReceiver(snapshotReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        receiverRegistered = false;
    }
}