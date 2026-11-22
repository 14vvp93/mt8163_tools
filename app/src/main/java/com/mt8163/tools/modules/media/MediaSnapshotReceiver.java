package com.mt8163.tools.modules.media;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class MediaSnapshotReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        String action = intent.getAction();
        if (MediaSettingsStore.ACTION_STATE_CHANGED.equals(action)) {
            saveSnapshot(context, intent);
        } else if (MediaSettingsStore.ACTION_RESTORE_REQUEST.equals(action)) {
            returnSnapshot(context);
        }
    }

    private void saveSnapshot(Context context, Intent intent) {
        String packageName = intent.getStringExtra(
                MediaSettingsStore.EXTRA_SOURCE_PACKAGE);
        String playbackState = intent.getStringExtra(
                MediaSettingsStore.EXTRA_PLAYBACK_STATE);
        boolean isForeground = intent.getBooleanExtra(
                MediaSettingsStore.EXTRA_IS_FOREGROUND, false);

        boolean saved = MediaSettingsStore.saveSnapshot(
                context, packageName, playbackState, isForeground);
        setResultCode(saved ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
    }

    private void returnSnapshot(Context context) {
        try {
            Bundle result = new Bundle();
            result.putString(MediaSettingsStore.EXTRA_SOURCE_PACKAGE,
                    MediaSettingsStore.getSourcePackage(context));
            result.putString(MediaSettingsStore.EXTRA_PLAYBACK_STATE,
                    MediaSettingsStore.getPlaybackState(context));
            result.putBoolean(MediaSettingsStore.EXTRA_IS_FOREGROUND,
                    MediaSettingsStore.isForeground(context));
            result.putBoolean(MediaSettingsStore.EXTRA_ENABLED,
                    MediaSettingsStore.isEnabled(context));
            result.putBoolean(MediaSettingsStore.EXTRA_RESTORE_BACKGROUND_MEDIA,
                    MediaSettingsStore.isRestoreBackgroundMedia(context));
            result.putBoolean(MediaSettingsStore.EXTRA_RESTORE_BACKGROUND_BLUETOOTH,
                    MediaSettingsStore.isRestoreBackgroundBluetooth(context));
            setResultExtras(result);
            setResultCode(Activity.RESULT_OK);
        } catch (RuntimeException error) {
            setResultCode(Activity.RESULT_CANCELED);
        }
    }
}
