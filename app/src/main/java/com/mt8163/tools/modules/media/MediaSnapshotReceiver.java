package com.mt8163.tools.modules.media;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class MediaSnapshotReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !MediaSettingsStore.ACTION_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }

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
}
