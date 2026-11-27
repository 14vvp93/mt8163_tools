package com.mt8163.tools.modules.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import com.mt8163.tools.R;

public final class MediaModuleView {
    private final Context context;
    private final Switch restoreBackgroundMediaSwitch;
    private final Switch restoreBackgroundBluetoothSwitch;
    private final Switch playbackNotificationsSwitch;
    private final TextView mediaSnapshotSourceText;
    private final TextView mediaSnapshotPackageText;
    private final TextView mediaSnapshotPlaybackText;
    private final TextView mediaSnapshotWindowText;
    private boolean snapshotReceiverRegistered;
    private boolean suppressSettingCallbacks;

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
        playbackNotificationsSwitch = root.findViewById(R.id.media_playback_notifications);
        mediaSnapshotSourceText = root.findViewById(R.id.media_snapshot_source);
        mediaSnapshotPackageText = root.findViewById(R.id.media_snapshot_package);
        mediaSnapshotPlaybackText = root.findViewById(R.id.media_snapshot_playback);
        mediaSnapshotWindowText = root.findViewById(R.id.media_snapshot_window);

        restoreBackgroundMediaSwitch.setChecked(MediaSettingsStore.isRestoreBackgroundMedia(this.context));
        restoreBackgroundBluetoothSwitch.setChecked(
                MediaSettingsStore.isRestoreBackgroundBluetooth(this.context));
        playbackNotificationsSwitch.setChecked(
                MediaSettingsStore.isPlaybackNotifications(this.context));

        restoreBackgroundMediaSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (suppressSettingCallbacks) return;
            boolean successful = MediaSettingsStore.setRestoreBackgroundMedia(
                    this.context, checked);
            handleSettingsResult(button, successful,
                    MediaSettingsStore.isRestoreBackgroundMedia(this.context));
        });
        restoreBackgroundBluetoothSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (suppressSettingCallbacks) return;
            boolean successful = MediaSettingsStore.setRestoreBackgroundBluetooth(
                    this.context, checked);
            handleSettingsResult(button, successful,
                    MediaSettingsStore.isRestoreBackgroundBluetooth(this.context));
        });
        playbackNotificationsSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (suppressSettingCallbacks) return;
            boolean successful = MediaSettingsStore.setPlaybackNotifications(
                    this.context, checked);
            handleSettingsResult(button, successful,
                    MediaSettingsStore.isPlaybackNotifications(this.context));
        });

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

    private void handleSettingsResult(Switch target, boolean successful,
                                       boolean persistedValue) {
        if (successful) return;

        suppressSettingCallbacks = true;
        target.setChecked(persistedValue);
        suppressSettingCallbacks = false;
    }

    private void refreshMediaSnapshot() {
        String packageName = MediaSettingsStore.getSourcePackage(context);
        boolean hasSnapshot = packageName != null && !packageName.trim().isEmpty();

        if (!hasSnapshot) {
            setBadge(mediaSnapshotSourceText, R.string.media_unknown,
                    R.color.media_badge_danger_background, R.color.media_badge_danger_text);
            setBadge(mediaSnapshotPackageText, R.string.media_unknown,
                    R.color.media_badge_danger_background, R.color.media_badge_danger_text);
            setBadge(mediaSnapshotPlaybackText, R.string.media_unknown,
                    R.color.media_badge_danger_background, R.color.media_badge_danger_text);
            setBadge(mediaSnapshotWindowText, R.string.media_unknown,
                    R.color.media_badge_danger_background, R.color.media_badge_danger_text);
            return;
        }

        if (MediaSettingsStore.isBluetoothSource(context)) {
            setBadge(mediaSnapshotSourceText, R.string.media_snapshot_bluetooth,
                    R.color.media_badge_info_background, R.color.media_badge_info_text);
        } else {
            setBadge(mediaSnapshotSourceText, R.string.media_snapshot_media_player,
                    R.color.media_badge_success_background, R.color.media_badge_success_text);
        }
        setBadge(mediaSnapshotPackageText, packageName,
                R.color.media_badge_neutral_background, R.color.media_badge_neutral_text);

        String playbackState = MediaSettingsStore.getPlaybackState(context);
        if (MediaSettingsStore.PLAYBACK_PLAYING.equals(playbackState)) {
            setBadge(mediaSnapshotPlaybackText, R.string.media_playing,
                    R.color.media_badge_success_background, R.color.media_badge_success_text);
        } else if (MediaSettingsStore.PLAYBACK_PAUSED.equals(playbackState)) {
            setBadge(mediaSnapshotPlaybackText, R.string.media_paused,
                    R.color.media_badge_info_background, R.color.media_badge_info_text);
        } else {
            setBadge(mediaSnapshotPlaybackText, R.string.media_unknown,
                    R.color.media_badge_danger_background, R.color.media_badge_danger_text);
        }

        if (MediaSettingsStore.isForeground(context)) {
            setBadge(mediaSnapshotWindowText, R.string.media_foreground,
                    R.color.media_badge_success_background, R.color.media_badge_success_text);
        } else {
            setBadge(mediaSnapshotWindowText, R.string.media_background,
                    R.color.media_badge_info_background, R.color.media_badge_info_text);
        }
    }

    private void setBadge(TextView view, int textRes, int backgroundRes, int textColorRes) {
        setBadge(view, context.getString(textRes), backgroundRes, textColorRes);
    }

    private void setBadge(TextView view, String text, int backgroundRes, int textColorRes) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(context.getColor(backgroundRes));
        background.setCornerRadius(context.getResources().getDimension(R.dimen.media_badge_corner_radius));
        view.setBackground(background);
        view.setTextColor(context.getColor(textColorRes));
        view.setText(text);
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