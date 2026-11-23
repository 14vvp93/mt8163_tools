package com.mt8163.tools.modules.media;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class MediaSettingsStoreTest {
    private Context context;
    private SharedPreferences modulePreferences;
    private SharedPreferences hookPreferences;
    private SharedPreferences statePreferences;
    private SharedPreferences.Editor moduleEditor;
    private SharedPreferences.Editor hookEditor;
    private SharedPreferences.Editor stateEditor;

    @Before
    public void setUp() {
        context = mock(Context.class);
        modulePreferences = mock(SharedPreferences.class);
        hookPreferences = mock(SharedPreferences.class);
        statePreferences = mock(SharedPreferences.class);
        moduleEditor = mock(SharedPreferences.Editor.class);
        hookEditor = mock(SharedPreferences.Editor.class);
        stateEditor = mock(SharedPreferences.Editor.class);

        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences("mt8163_tools", Context.MODE_PRIVATE))
                .thenReturn(modulePreferences);
        when(context.getSharedPreferences("media_hook_config", Context.MODE_WORLD_READABLE))
                .thenReturn(hookPreferences);
        when(context.getSharedPreferences("media_state", Context.MODE_PRIVATE))
                .thenReturn(statePreferences);

        when(modulePreferences.edit()).thenReturn(moduleEditor);
        when(hookPreferences.edit()).thenReturn(hookEditor);
        when(statePreferences.edit()).thenReturn(stateEditor);

        when(moduleEditor.putBoolean(anyString(), anyBoolean())).thenReturn(moduleEditor);
        when(hookEditor.putBoolean(anyString(), anyBoolean())).thenReturn(hookEditor);
        when(stateEditor.putString(anyString(), anyString())).thenReturn(stateEditor);
        when(stateEditor.putBoolean(anyString(), anyBoolean())).thenReturn(stateEditor);

        when(moduleEditor.putBoolean(
                MediaSettingsStore.EXTRA_RESTORE_BACKGROUND_MEDIA, true))
                .thenReturn(moduleEditor);
        when(moduleEditor.putBoolean(
                MediaSettingsStore.EXTRA_RESTORE_BACKGROUND_BLUETOOTH, true))
                .thenReturn(moduleEditor);
        when(hookEditor.putBoolean(
                MediaSettingsStore.EXTRA_ENABLED, false)).thenReturn(hookEditor);
        when(hookEditor.putBoolean(
                MediaSettingsStore.EXTRA_RESTORE_BACKGROUND_MEDIA, false))
                .thenReturn(hookEditor);
        when(hookEditor.putBoolean(
                MediaSettingsStore.EXTRA_RESTORE_BACKGROUND_BLUETOOTH, false))
                .thenReturn(hookEditor);
        when(stateEditor.putString(
                MediaSettingsStore.EXTRA_SOURCE_PACKAGE, "com.example.player"))
                .thenReturn(stateEditor);
        when(stateEditor.putString(
                MediaSettingsStore.EXTRA_PLAYBACK_STATE, MediaSettingsStore.PLAYBACK_PLAYING))
                .thenReturn(stateEditor);
        when(stateEditor.putBoolean(
                MediaSettingsStore.EXTRA_IS_FOREGROUND, true)).thenReturn(stateEditor);
        when(stateEditor.commit()).thenReturn(true);
        when(moduleEditor.commit()).thenReturn(true);
        when(hookEditor.commit()).thenReturn(true);
    }

    @Test
    public void normalizesAllSupportedPlayingStates() {
        String[] states = {
                "playing",
                "start",
                "write",
                "fast_forwarding",
                "rewinding"
        };

        for (String state : states) {
            assertEquals(state, MediaSettingsStore.PLAYBACK_PLAYING,
                    MediaSettingsStore.normalizePlaybackState(state));
        }
    }

    @Test
    public void normalizesPlayingAliasesWithoutCaseSensitivity() {
        assertEquals(MediaSettingsStore.PLAYBACK_PLAYING,
                MediaSettingsStore.normalizePlaybackState("PLAYING"));
        assertEquals(MediaSettingsStore.PLAYBACK_PLAYING,
                MediaSettingsStore.normalizePlaybackState(" Start "));
        assertEquals(MediaSettingsStore.PLAYBACK_PLAYING,
                MediaSettingsStore.normalizePlaybackState(" WRITE "));
        assertEquals(MediaSettingsStore.PLAYBACK_PLAYING,
                MediaSettingsStore.normalizePlaybackState("FAST_FORWARDING"));
        assertEquals(MediaSettingsStore.PLAYBACK_PLAYING,
                MediaSettingsStore.normalizePlaybackState("ReWiNdInG"));
    }

    @Test
    public void normalizesPausedStateWithoutCaseSensitivity() {
        assertEquals(MediaSettingsStore.PLAYBACK_PAUSED,
                MediaSettingsStore.normalizePlaybackState("paused"));
        assertEquals(MediaSettingsStore.PLAYBACK_PAUSED,
                MediaSettingsStore.normalizePlaybackState(" PaUsEd "));
        assertEquals(MediaSettingsStore.PLAYBACK_PAUSED,
                MediaSettingsStore.normalizePlaybackState("\tPAUSED\n"));
    }

    @Test
    public void mapsNullAndBlankValuesToUnknown() {
        assertEquals(MediaSettingsStore.PLAYBACK_UNKNOWN,
                MediaSettingsStore.normalizePlaybackState(null));
        assertEquals(MediaSettingsStore.PLAYBACK_UNKNOWN,
                MediaSettingsStore.normalizePlaybackState(""));
        assertEquals(MediaSettingsStore.PLAYBACK_UNKNOWN,
                MediaSettingsStore.normalizePlaybackState("   "));
        assertEquals(MediaSettingsStore.PLAYBACK_UNKNOWN,
                MediaSettingsStore.normalizePlaybackState("\t\n"));
    }

    @Test
    public void mapsUnsupportedValuesToUnknown() {
        String[] states = {
                "buffering",
                "stopped",
                "stop",
                "fast forwarding",
                "rewind",
                "unknown"
        };

        for (String state : states) {
            assertEquals(state, MediaSettingsStore.PLAYBACK_UNKNOWN,
                    MediaSettingsStore.normalizePlaybackState(state));
        }
    }

    @Test
    public void publishIgnoresNullContext() {
        MediaSettingsStore.publish(null);
    }

    @Test
    public void publishesPersistedConfiguration() {
        when(modulePreferences.getBoolean("module_media", false)).thenReturn(true);
        when(modulePreferences.getBoolean(
                MediaSettingsStore.EXTRA_RESTORE_BACKGROUND_MEDIA, false)).thenReturn(true);
        when(modulePreferences.getBoolean(
                MediaSettingsStore.EXTRA_RESTORE_BACKGROUND_BLUETOOTH, false)).thenReturn(false);

        MediaSettingsStore.publish(context);

        verify(hookEditor).putBoolean(MediaSettingsStore.EXTRA_ENABLED, true);
        verify(hookEditor).putBoolean(
                MediaSettingsStore.EXTRA_RESTORE_BACKGROUND_MEDIA, true);
        verify(hookEditor).putBoolean(
                MediaSettingsStore.EXTRA_RESTORE_BACKGROUND_BLUETOOTH, false);
        verify(hookEditor).commit();
        verify(context).sendBroadcast(any(Intent.class));
    }

    @Test
    public void readsModuleFlags() {
        when(modulePreferences.getBoolean("module_media", false)).thenReturn(true);
        when(modulePreferences.getBoolean(
                MediaSettingsStore.EXTRA_RESTORE_BACKGROUND_MEDIA, false)).thenReturn(true);
        when(modulePreferences.getBoolean(
                MediaSettingsStore.EXTRA_RESTORE_BACKGROUND_BLUETOOTH, false)).thenReturn(true);

        assertTrue(MediaSettingsStore.isEnabled(context));
        assertTrue(MediaSettingsStore.isRestoreBackgroundMedia(context));
        assertTrue(MediaSettingsStore.isRestoreBackgroundBluetooth(context));
        assertFalse(MediaSettingsStore.isEnabled(null));
        assertFalse(MediaSettingsStore.isRestoreBackgroundMedia(null));
        assertFalse(MediaSettingsStore.isRestoreBackgroundBluetooth(null));
    }

    @Test
    public void savesAndNormalizesPlaybackSnapshot() {
        assertTrue(MediaSettingsStore.saveSnapshot(
                context, "com.example.player", " START ", true));

        verify(stateEditor).putString(
                MediaSettingsStore.EXTRA_SOURCE_PACKAGE, "com.example.player");
        verify(stateEditor).putString(
                MediaSettingsStore.EXTRA_PLAYBACK_STATE, MediaSettingsStore.PLAYBACK_PLAYING);
        verify(stateEditor).putBoolean(MediaSettingsStore.EXTRA_IS_FOREGROUND, true);
        verify(stateEditor).commit();
    }

    @Test
    public void savesSafeDefaultsForNullSnapshotFields() {
        assertTrue(MediaSettingsStore.saveSnapshot(context, null, null, false));

        verify(stateEditor).putString(MediaSettingsStore.EXTRA_SOURCE_PACKAGE, "");
        verify(stateEditor).putString(
                MediaSettingsStore.EXTRA_PLAYBACK_STATE, MediaSettingsStore.PLAYBACK_UNKNOWN);
    }

    @Test
    public void returnsFalseWhenSnapshotPersistenceThrows() {
        when(context.getApplicationContext()).thenThrow(new IllegalStateException());

        assertFalse(MediaSettingsStore.saveSnapshot(context, "player", "playing", true));
    }

    @Test
    public void returnsSafeDefaultsForNullContextStateReads() {
        assertEquals("", MediaSettingsStore.getSourcePackage(null));
        assertEquals("", MediaSettingsStore.getPlaybackState(null));
        assertFalse(MediaSettingsStore.isForeground(null));
    }
}
