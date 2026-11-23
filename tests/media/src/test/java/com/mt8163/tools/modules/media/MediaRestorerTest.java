package com.mt8163.tools.modules.media;

import android.content.Context;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class MediaRestorerTest {

    @Test
    public void skipsBluetoothRestoreForNonPlayingSnapshots() {
        Context context = mockContext();
        String[] states = {null, "", "unknown", "paused", "stopped"};
        AtomicInteger completions = new AtomicInteger();

        for (String playbackState : states) {
            MediaRestorer.Cancellable operation = MediaRestorer.restore(
                    context,
                    "com.autochips.bluetooth",
                    playbackState,
                    false,
                    false,
                    false,
                    completions::incrementAndGet
            );

            assertNotNull(operation);
            operation.cancel();
        }

        assertEquals(states.length, completions.get());
    }

    @Test
    public void rejectsMissingContextAndCompletesOnce() {
        AtomicInteger completions = new AtomicInteger();

        MediaRestorer.Cancellable operation = MediaRestorer.restore(
                null,
                "com.autochips.bluetooth",
                "playing",
                false,
                false,
                false,
                completions::incrementAndGet
        );

        assertNotNull(operation);
        operation.cancel();
        assertEquals(1, completions.get());
    }

    @Test
    public void rejectsMissingPackageAndCompletesOnce() {
        AtomicInteger completions = new AtomicInteger();

        MediaRestorer.Cancellable operation = MediaRestorer.restore(
                mockContext(),
                null,
                "playing",
                false,
                false,
                false,
                completions::incrementAndGet
        );

        assertNotNull(operation);
        operation.cancel();
        assertEquals(1, completions.get());
    }

    @Test
    public void ignoresCompletionCallbackRuntimeException() {
        MediaRestorer.Cancellable operation = MediaRestorer.restore(
                null,
                "com.autochips.bluetooth",
                "playing",
                false,
                false,
                false,
                () -> {
                    throw new IllegalStateException("test");
                }
        );

        assertNotNull(operation);
    }

    private static Context mockContext() {
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        return context;
    }
}
