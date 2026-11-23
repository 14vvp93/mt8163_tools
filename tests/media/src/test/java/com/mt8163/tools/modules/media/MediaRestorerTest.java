package com.mt8163.tools.modules.media;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
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
    private static final String BLUETOOTH_PACKAGE = "com.autochips.bluetooth";
    private static final String CALLBACK_DESCRIPTOR =
            "com.hcn.bluetooth.service.IBluetoothMusicService";

    private Context context;
    private MediaSessionManager manager;
    private final List<MediaSession> mediaSessions = new ArrayList<>();

    @Before
    public void setUp() {
        context = mock(Context.class);
        manager = mock(MediaSessionManager.class);

        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSystemService(Context.MEDIA_SESSION_SERVICE)).thenReturn(manager);
        when(manager.getActiveSessions(null)).thenReturn(Collections.emptyList());
    }

    @After
    public void tearDown() {
        for (MediaSession session : mediaSessions) {
            session.release();
        }
        mediaSessions.clear();
    }

    @Test
    public void skipsBluetoothRestoreForNonPlayingSnapshots() {
        String[] states = {null, "", "unknown", "paused", "stopped"};
        AtomicInteger completions = new AtomicInteger();

        for (String playbackState : states) {
            MediaRestorer.Cancellable operation = MediaRestorer.restore(
                    context,
                    BLUETOOTH_PACKAGE,
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
                BLUETOOTH_PACKAGE,
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
        AtomicInteger plays = new AtomicInteger();
        when(controller.getTransportControls()).thenReturn(createTransportControls(plays));
        AtomicInteger completions = new AtomicInteger();

        MediaRestorer.Cancellable operation = startRestore(completions);
        MediaSessionManager.OnActiveSessionsChangedListener listener = captureListener();

        listener.onActiveSessionsChanged(Collections.singletonList(
                mockController("com.example.other")));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, plays.get());

        listener.onActiveSessionsChanged(Collections.singletonList(controller));
        shadowOf(Looper.getMainLooper()).idle();
        listener.onActiveSessionsChanged(Collections.singletonList(controller));
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, plays.get());
        verify(manager, times(1)).removeOnActiveSessionsChangedListener(listener);
        assertEquals(1, completions.get());

        operation.cancel();
        assertEquals(1, completions.get());
    }

    @Test
    public void cancelsRestoreAndIgnoresLateSessionCallback() {
        MediaController controller = mockController(SOURCE_PACKAGE);
        AtomicInteger plays = new AtomicInteger();
        when(controller.getTransportControls()).thenReturn(createTransportControls(plays));
        AtomicInteger completions = new AtomicInteger();

        MediaRestorer.Cancellable operation = startRestore(completions);
        MediaSessionManager.OnActiveSessionsChangedListener listener = captureListener();

        operation.cancel();
        listener.onActiveSessionsChanged(Collections.singletonList(controller));
        shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS);

        assertEquals(0, plays.get());
        verify(manager, times(1)).removeOnActiveSessionsChangedListener(listener);
        assertEquals(1, completions.get());
    }

    @Test
    public void timesOutAfterFiveSecondsAndIgnoresLateSessionCallback() {
        MediaController controller = mockController(SOURCE_PACKAGE);
        AtomicInteger plays = new AtomicInteger();
        when(controller.getTransportControls()).thenReturn(createTransportControls(plays));
        AtomicInteger completions = new AtomicInteger();

        startRestore(completions);
        MediaSessionManager.OnActiveSessionsChangedListener listener = captureListener();

        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS);
        listener.onActiveSessionsChanged(Collections.singletonList(controller));
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, plays.get());
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

    @Test
    public void bluetoothCancelBeforeBinderConnectionPreventsLateInitialization() {
        AtomicReference<ServiceConnection> connection = new AtomicReference<>();
        RecordingBinder binder = new RecordingBinder();
        AtomicInteger completions = new AtomicInteger();
        prepareBluetoothService(connection);

        MediaRestorer.Cancellable operation = startBluetoothRestore(completions);
        assertNotNull(connection.get());

        operation.cancel();
        connection.get().onServiceConnected(
                new ComponentName("com.hcn.bluetoothservice",
                        "com.hcn.bluetooth.service.BluetoothMusicService"),
                binder);

        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, binder.getRegisterCalls());
        assertEquals(0, binder.getPlayCalls());
        assertEquals(1, completions.get());
        verify(context, times(1)).unbindService(connection.get());
    }

    @Test
    public void bluetoothThirtySecondTimeoutPreventsLateBinderInitialization() {
        AtomicReference<ServiceConnection> connection = new AtomicReference<>();
        RecordingBinder binder = new RecordingBinder();
        AtomicInteger completions = new AtomicInteger();
        prepareBluetoothService(connection);

        MediaRestorer.Cancellable operation = startBluetoothRestore(completions);
        assertNotNull(connection.get());

        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS);
        connection.get().onServiceConnected(
                new ComponentName("com.hcn.bluetoothservice",
                        "com.hcn.bluetooth.service.BluetoothMusicService"),
                binder);

        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, binder.getRegisterCalls());
        assertEquals(0, binder.getPlayCalls());
        assertEquals(1, completions.get());
        verify(context, times(1)).unbindService(connection.get());

        operation.cancel();
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

    private MediaRestorer.Cancellable startBluetoothRestore(AtomicInteger completions) {
        return MediaRestorer.restore(
                context,
                BLUETOOTH_PACKAGE,
                "playing",
                false,
                false,
                false,
                completions::incrementAndGet
        );
    }

    private void prepareBluetoothService(AtomicReference<ServiceConnection> connection) {
        MediaController vendorController = mock(MediaController.class);
        when(vendorController.getTag()).thenReturn("A2dpMediaBrowserService");
        when(manager.getActiveSessions(null)).thenReturn(
                Collections.singletonList(vendorController));

        doAnswer(invocation -> {
            connection.set(invocation.getArgument(1));
            return true;
        }).when(context).bindService(any(), any(ServiceConnection.class), any(Integer.class));
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

    private MediaController.TransportControls createTransportControls(AtomicInteger plays) {
        MediaSession session = new MediaSession(
                RuntimeEnvironment.getApplication(), "MediaRestorerTest");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                plays.incrementAndGet();
            }
        }, new Handler(Looper.getMainLooper()));
        session.setActive(true);
        mediaSessions.add(session);

        MediaController controller = new MediaController(
                RuntimeEnvironment.getApplication(), session.getSessionToken());
        return controller.getTransportControls();
    }

    private static final class RecordingBinder extends Binder {
        private final AtomicInteger registerCalls = new AtomicInteger();
        private final AtomicInteger playCalls = new AtomicInteger();

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == 10) {
                registerCalls.incrementAndGet();
                if (reply != null) reply.writeNoException();
                return true;
            }
            if (code == 5) {
                playCalls.incrementAndGet();
                if (reply != null) reply.writeNoException();
                return true;
            }
            if (code == 11) {
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        int getRegisterCalls() {
            return registerCalls.get();
        }

        int getPlayCalls() {
            return playCalls.get();
        }
    }
}
