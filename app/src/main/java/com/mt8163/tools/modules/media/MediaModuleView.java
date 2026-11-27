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
    private final TextView mediaPackage;
    private final TextView mediaPlayback;
    private final TextView mediaWindow;
    private final TextView bluetoothPackage;
    private final TextView bluetoothPlayback;
    private final TextView bluetoothWindow;
    private boolean receiverRegistered;

    private final BroadcastReceiver snapshotReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshSnapshots();
        }
    };

    public MediaModuleView(View root, Context context) {
        this.context = context.getApplicationContext();
        restoreMedia = root.findViewById(R.id.media_restore_background_media);
        restoreBluetooth = root.findViewById(R.id.media_restore_background_bluetooth);
        mediaPackage = root.findViewById(R.id.media_snapshot_package);
        mediaPlayback = root.findViewById(R.id.media_snapshot_playback);
        mediaWindow = root.findViewById(R.id.media_snapshot_window);
        bluetoothPackage = root.findViewById(R.id.bluetooth_snapshot_package);
        bluetoothPlayback = root.findViewById(R.id.bluetooth_snapshot_playback);
        bluetoothWindow = root.findViewById(R.id.bluetooth_snapshot_window);

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
                refreshSnapshots();
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                unregisterSnapshotReceiver();
            }
        });

        if (root.isAttachedToWindow()) registerSnapshotReceiver();
        refreshSnapshots();
    }

    private void refreshSnapshots() {
        mediaPackage.setText(displayPackage(
                MediaSettingsStore.getMediaSnapshotPackage(context)));
        mediaPlayback.setText(displayPlayback(
                MediaSettingsStore.getMediaSnapshotPlaybackState(context)));
        mediaWindow.setText(MediaSettingsStore.getMediaSnapshotWindowState(context));

        bluetoothPackage.setText(displayPackage(
                MediaSettingsStore.getBluetoothSnapshotPackage(context)));
        bluetoothPlayback.setText(displayPlayback(
                MediaSettingsStore.getBluetoothSnapshotPlaybackState(context)));
        bluetoothWindow.setText(MediaSettingsStore.getBluetoothSnapshotWindowState(context));
    }

    private String displayPackage(String value) {
        return value == null || value.trim().isEmpty()
                ? context.getString(R.string.media_unknown) : value;
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