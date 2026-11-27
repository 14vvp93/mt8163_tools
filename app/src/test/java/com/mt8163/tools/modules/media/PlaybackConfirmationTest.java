package com.mt8163.tools.modules.media;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackConfirmationTest {
    @Test
    public void uncommittedPlaybackIsBeforeDispatch() {
        assertTrue(PlaybackConfirmation.startedBeforeDispatch(false, 0L, 100L));
    }

    @Test
    public void missingTimestampIsStale() {
        assertTrue(PlaybackConfirmation.startedBeforeDispatch(true, 0L, 100L));
    }

    @Test
    public void timestampAtDispatchIsStale() {
        assertTrue(PlaybackConfirmation.startedBeforeDispatch(true, 100L, 100L));
    }

    @Test
    public void timestampBeforeDispatchIsStale() {
        assertTrue(PlaybackConfirmation.startedBeforeDispatch(true, 99L, 100L));
    }

    @Test
    public void timestampAfterDispatchIsNew() {
        assertFalse(PlaybackConfirmation.startedBeforeDispatch(true, 101L, 100L));
    }
}
