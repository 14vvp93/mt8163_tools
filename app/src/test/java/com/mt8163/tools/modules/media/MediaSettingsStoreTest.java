package com.mt8163.tools.modules.media;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class MediaSettingsStoreTest {
    @Test
    public void normalizesPlayingStates() {
        assertEquals(MediaSettingsStore.PLAYBACK_PLAYING,
                MediaSettingsStore.normalizePlaybackState("playing"));
        assertEquals(MediaSettingsStore.PLAYBACK_PLAYING,
                MediaSettingsStore.normalizePlaybackState("start"));
        assertEquals(MediaSettingsStore.PLAYBACK_PLAYING,
                MediaSettingsStore.normalizePlaybackState("write"));
    }

    @Test
    public void normalizesPausedState() {
        assertEquals(MediaSettingsStore.PLAYBACK_PAUSED,
                MediaSettingsStore.normalizePlaybackState("PAUSED"));
    }

    @Test
    public void unknownValuesRemainUnknown() {
        assertEquals(MediaSettingsStore.PLAYBACK_UNKNOWN,
                MediaSettingsStore.normalizePlaybackState(null));
        assertEquals(MediaSettingsStore.PLAYBACK_UNKNOWN,
                MediaSettingsStore.normalizePlaybackState("stopped"));
        assertEquals(MediaSettingsStore.PLAYBACK_UNKNOWN,
                MediaSettingsStore.normalizePlaybackState("invalid"));
    }
}
