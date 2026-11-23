package com.mt8163.tools.modules.media;

import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class MediaRestorerTest {
    private static final String SOURCE_PACKAGE = "com.example.player";

    private Context context;
    private MediaSessionManager manager;

    @Before
    public void setUp() {
        context = mock(Context.class);
        manager = mock(MediaSessionManager.class);

        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSystemService(Context.MEDIA_SESSION_SERVICE)).thenReturn(manager);
        when(manager.getActiveSessions(null)).thenReturn(Collections.emptyList());
    }

    @Test
    public void skipsBluetoothRestoreForNonPlayingSnapshots() {
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
                context,
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
    public void playsMatchingSessionOnceAndRemovesListener() {
        MediaController controller = mockController(SOURCE_PACKAGE);
        MediaController.TransportControls controls = mock(MediaController.TransportControls.class);
        when(controller.getTransportControls()).thenReturn(controls);
        AtomicInteger completions = new AtomicInteger();

        MediaRestorer.Cancellable operation = startRestore(completions);
        MediaSessionManager.OnActiveSessionsChangedListener listener = captureListener();

        listener.onActiveSessionsChanged(Collections.singletonList(
                mockController("com.example.other")));
        verify(controls, never()).play();

        listener.onActiveSessionsChanged(Collections.singletonList(controller));
        listener.onActiveSessionsChanged(Collections.singletonList(controller));

        verify(controls, times(1)).play();
        verify(manager, times(1)).removeOnActiveSessionsChangedListener(listener);
        assertEquals(1, completions.get());

        operation.cancel();
        assertEquals(1, completions.get());
    }

    @Test
    public void cancelsRestoreAndIgnoresLateSessionCallback() {
        MediaController controller = mockController(SOURCE_PACKAGE);
        MediaController.TransportControls controls = mock(MediaController.TransportControls.class);
        when(controller.getTransportControls()).thenReturn(controls);
        AtomicInteger completions = new AtomicInteger();

        MediaRestorer.Cancellable operation = startRestore(completions);
        MediaSessionManager.OnActiveSessionsChangedListener listener = captureListener();

        operation.cancel();
        listener.onActiveSessionsChanged(Collections.singletonList(controller));
        shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS);

        verify(controls, never()).play();
        verify(manager, times(1)).removeOnActiveSessionsChangedListener(listener);
        assertEquals(1, completions.get());
    }

    @Test
    public void timesOutAfterFiveSecondsAndIgnoresLateSessionCallback() {
        MediaController controller = mockController(SOURCE_PACKAGE);
        MediaController.TransportControls controls = mock(MediaController.TransportControls.class);
        when(controller.getTransportControls()).thenReturn(controls);
        AtomicInteger completions = new AtomicInteger();

        startRestore(completions);
        MediaSessionManager.OnActiveSessionsChangedListener listener = captureListener();

        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS);
        listener.onActiveSessionsChanged(Collections.singletonList(controller));

        verify(controls, never()).play();
        verify(manager, times(1)).removeOnActiveSessionsChangedListener(listener);
        assertEquals(1, completions.get());
    }

    @Test
    public void skipsPlayForNonPlayingStandardSnapshot() {
        AtomicInteger completions = new AtomicInteger();

        MediaRestorer.restore(
                context,
                SOURCE_PACKAGE,
                "paused",
                false,
                false,
                false,
                completions::incrementAndGet
        );

        verify(manager, never()).addOnActiveSessionsChangedListener(
                any(), any(), any(Handler.class));
        assertEquals(1, completions.get());
    }

    private MediaRestorer.Cancellable startRestore(AtomicInteger completions) {
        return MediaRestorer.restore(
                context,
                SOURCE_PACKAGE,
                "playing",
                false,
                false,
                false,
                completions::incrementAndGet
        );
    }

    private MediaSessionManager.OnActiveSessionsChangedListener captureListener() {
        ArgumentCaptor<MediaSessionManager.OnActiveSessionsChangedListener> captor =
                ArgumentCaptor.forClass(
                        MediaSessionManager.OnActiveSessionsChangedListener.class);

        verify(manager).addOnActiveSessionsChangedListener(
                captor.capture(), isNull(), any(Handler.class));
        return captor.getValue();
    }

    private static MediaController mockController(String packageName) {
        MediaController controller = mock(MediaController.class);
        when(controller.getPackageName()).thenReturn(packageName);
        return controller;
    }
}
