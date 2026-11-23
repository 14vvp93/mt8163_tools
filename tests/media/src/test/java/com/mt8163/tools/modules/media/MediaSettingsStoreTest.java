package com.mt8163.tools.modules.media;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class MediaSettingsStoreTest {
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
}
