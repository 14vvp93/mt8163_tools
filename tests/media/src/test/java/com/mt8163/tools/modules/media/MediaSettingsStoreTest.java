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
        assertEquals(MediaSettingsStore.PLAYBACK_PLAYING,
                MediaSettingsStore.normalizePlaybackState("fast_forwarding"));
        assertEquals(MediaSettingsStore.PLAYBACK_PLAYING,
                MediaSettingsStore.normalizePlaybackState("rewinding"));
    }

    @Test
    public void normalizationIgnoresCaseAndWhitespace() {
        assertEquals(MediaSettingsStore.PLAYBACK_PLAYING,
                MediaSettingsStore.normalizePlaybackState("  PLAYING "));
        assertEquals(MediaSettingsStore.PLAYBACK_PAUSED,
                MediaSettingsStore.normalizePlaybackState(" PaUsEd "));
    }

    @Test
    public void normalizesPausedState() {
        assertEquals(MediaSettingsStore.PLAYBACK_PAUSED,
                MediaSettingsStore.normalizePlaybackState("paused"));
    }

    @Test
    public void mapsNullAndUnknownValuesToUnknown() {
        assertEquals(MediaSettingsStore.PLAYBACK_UNKNOWN,
                MediaSettingsStore.normalizePlaybackState(null));
        assertEquals(MediaSettingsStore.PLAYBACK_UNKNOWN,
                MediaSettingsStore.normalizePlaybackState(""));
        assertEquals(MediaSettingsStore.PLAYBACK_UNKNOWN,
                MediaSettingsStore.normalizePlaybackState("buffering"));
    }
}
