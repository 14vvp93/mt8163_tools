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
    private final Switch restoreBackgroundMediaSwitch;
    private final Switch restoreBackgroundBluetoothSwitch;
    private final TextView mediaSnapshotSourceText;
    private final TextView mediaSnapshotPackageText;
    private final TextView mediaSnapshotPlaybackText;
    private final TextView mediaSnapshotWindowText;
    private boolean snapshotReceiverRegistered;

    private final BroadcastReceiver mediaSnapshotReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshMediaSnapshot();
        }
    };

    public MediaModuleView(View root, Context context) {
        this.context = context.getApplicationContext();
        restoreBackgroundMediaSwitch = root.findViewById(R.id.media_restore_background_media);
        restoreBackgroundBluetoothSwitch = root.findViewById(R.id.media_restore_background_bluetooth);
        mediaSnapshotSourceText = root.findViewById(R.id.media_snapshot_source);
        mediaSnapshotPackageText = root.findViewById(R.id.media_snapshot_package);
        mediaSnapshotPlaybackText = root.findViewById(R.id.media_snapshot_playback);
        mediaSnapshotWindowText = root.findViewById(R.id.media_snapshot_window);

        restoreBackgroundMediaSwitch.setChecked(MediaSettingsStore.isRestoreBackgroundMedia(this.context));
        restoreBackgroundBluetoothSwitch.setChecked(
                MediaSettingsStore.isRestoreBackgroundBluetooth(this.context));

        restoreBackgroundMediaSwitch.setOnCheckedChangeListener((button, checked) ->
                MediaSettingsStore.setRestoreBackgroundMedia(this.context, checked));
        restoreBackgroundBluetoothSwitch.setOnCheckedChangeListener((button, checked) ->
                MediaSettingsStore.setRestoreBackgroundBluetooth(this.context, checked));

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                registerMediaSnapshotReceiver();
                refreshMediaSnapshot();
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                unregisterMediaSnapshotReceiver();
            }
        });

        if (root.isAttachedToWindow()) registerMediaSnapshotReceiver();
        refreshMediaSnapshot();
    }

    private void refreshMediaSnapshot() {
        String packageName = MediaSettingsStore.getSourcePackage(context);
        boolean hasSnapshot = packageName != null && !packageName.trim().isEmpty();

        mediaSnapshotSourceText.setText(getMediaSnapshotSourceLabel(hasSnapshot));
        mediaSnapshotPackageText.setText(hasSnapshot
                ? packageName : context.getString(R.string.media_unknown));
        mediaSnapshotPlaybackText.setText(hasSnapshot
                ? formatMediaPlaybackState(MediaSettingsStore.getPlaybackState(context))
                : context.getString(R.string.media_unknown));
        mediaSnapshotWindowText.setText(hasSnapshot
                ? (MediaSettingsStore.isForeground(context)
                ? context.getString(R.string.media_foreground)
                : context.getString(R.string.media_background))
                : context.getString(R.string.media_unknown));
    }

    private int getMediaSnapshotSourceLabel(boolean hasSnapshot) {
        if (!hasSnapshot) return R.string.media_snapshot_unknown_source;
        return MediaSettingsStore.isBluetoothSource(context)
                ? R.string.media_snapshot_bluetooth
                : R.string.media_snapshot_media_player;
    }

    private String formatMediaPlaybackState(String value) {
        if (MediaSettingsStore.PLAYBACK_PLAYING.equals(value)) {
            return context.getString(R.string.media_playing);
        }
        if (MediaSettingsStore.PLAYBACK_PAUSED.equals(value)) {
            return context.getString(R.string.media_paused);
        }
        return context.getString(R.string.media_unknown);
    }

    private void registerMediaSnapshotReceiver() {
        if (snapshotReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(MediaSettingsStore.ACTION_SNAPSHOT_UPDATED);
        context.registerReceiver(mediaSnapshotReceiver, filter);
        snapshotReceiverRegistered = true;
    }

    private void unregisterMediaSnapshotReceiver() {
        if (!snapshotReceiverRegistered) return;
        try {
            context.unregisterReceiver(mediaSnapshotReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        snapshotReceiverRegistered = false;
    }
}