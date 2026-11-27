package com.mt8163.tools.modules.media;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.media.session.MediaSessionManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IInterface;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import com.mt8163.tools.core.EventLogger;
import com.mt8163.tools.modules.media.MediaSettingsStore;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MediaRestorer {
    private static final String BLUETOOTH_PACKAGE = "com.autochips.bluetooth";
    private static final String BLUETOOTH_ACTIVITY =
            "com.autochips.bluetooth.BtMusicActivity";
    private static final String BLUETOOTH_SERVICE_PACKAGE = "com.hcn.bluetoothservice";
    private static final String BLUETOOTH_SERVICE_CLASS =
            "com.hcn.bluetooth.service.BluetoothMusicService";
    private static final String BLUETOOTH_SERVICE_ACTION =
            "com.hcn.bluetooth.service.BluetoothMusicService";
    private static final String BLUETOOTH_DESCRIPTOR =
            "com.hcn.bluetooth.service.IBluetoothMusicService";
    private static final String BLUETOOTH_MEDIA_SESSION_PACKAGE = "com.android.bluetooth";
    private static final int TRANSACTION_SEND_AVRCP_COMMAND = 5;
    private static final long MEDIA_TIMEOUT_MS = 5000L;
    private static final long BLUETOOTH_TIMEOUT_MS = 30_000L;

    private static final ExecutorService BLUETOOTH_WORKER =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MediaBluetoothRestore");
                thread.setDaemon(true);
                return thread;
            });
    private static final AtomicBoolean BLUETOOTH_WORKER_BUSY = new AtomicBoolean();

    public interface Cancellable {
        void cancel();
    }

    public enum Result {
        RESUMED,
        FAILED,
        SKIPPED,
        CANCELLED
    }

    public interface Completion {
        void onComplete(Result result);
    }

    private MediaRestorer() {
    }

    public static Cancellable restore(Context context, String packageName, String state,
                                      boolean isForeground, boolean restoreBackgroundMedia,
                                      boolean restoreBackgroundBluetooth,
                                      Completion completion) {
        Context appContext = context == null ? null : context.getApplicationContext();
        if (appContext == null || packageName == null || packageName.isEmpty()) {
            logEvent(appContext, "restoreRejected", "Missing context or source package");
            notifyCompletion(completion, Result.FAILED);
            return () -> { };
        }

        boolean launchActivity = isForeground
                || (BLUETOOTH_PACKAGE.equals(packageName)
                ? restoreBackgroundBluetooth : restoreBackgroundMedia);
        boolean shouldPlay = isPlaying(state);

        logEvent(appContext, "restoreRequested", "Source: " + packageName
                + ", playback: " + state + ", foreground: " + isForeground
                + ", launchActivity: " + launchActivity
                + ", shouldPlay: " + shouldPlay);

        if (BLUETOOTH_PACKAGE.equals(packageName)) {
            if (!shouldPlay) {
                logEvent(appContext, "bluetoothRestoreSkipped", "Playback state is " + state);
                if (launchActivity) launchSourceActivity(appContext, packageName);
                notifyCompletion(completion, Result.SKIPPED);
                return () -> { };
            }
            BluetoothRestore restore = new BluetoothRestore(
                    appContext, packageName, launchActivity, completion);
            restore.start();
            return restore;
        }

        StandardRestore restore = new StandardRestore(
                appContext, packageName, launchActivity, shouldPlay, completion);
        restore.start();
        return restore;
    }

    private static boolean isPlaying(String state) {
        return MediaSettingsStore.PLAYBACK_PLAYING.equals(
                MediaSettingsStore.normalizePlaybackState(state));
    }

    private static boolean isVendorSession(MediaController controller) {
        return controller != null
                && BLUETOOTH_MEDIA_SESSION_PACKAGE.equals(controller.getPackageName());
    }

    private static final class StandardRestore implements Cancellable {
        private enum State {
            ACTIVE,
            PLAY_SENT,
            COMPLETED,
            CANCELLED
        }

        private final Context context;
        private final String packageName;
        private final boolean launchActivity;
        private final boolean playing;
        private final Completion completion;
        private final MediaSessionManager manager;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Object lock = new Object();

        private final AtomicReference<State> state =
                new AtomicReference<>(State.ACTIVE);
        private boolean listenerRegistered;
        private boolean controllerCallbackRegistered;
        private boolean playRequested;
        private MediaController activeController;

        private final MediaSessionManager.OnActiveSessionsChangedListener listener =
                this::onSessionsChanged;
        private final Runnable timeout = this::onTimeout;
        private final MediaController.Callback playbackCallback =
                new MediaController.Callback() {
                    @Override
                    public void onPlaybackStateChanged(PlaybackState playbackState) {
                        if (playbackState == null) return;
                        if (playbackState.getState() == PlaybackState.STATE_PLAYING) {
                            complete(Result.RESUMED);
                        } else if (playbackState.getState() == PlaybackState.STATE_ERROR
                                && playRequested) {
                            complete(Result.FAILED);
                        }
                    }
                };

        StandardRestore(Context context, String packageName, boolean launchActivity,
                        boolean shouldPlay, Completion completion) {
            this.context = context;
            this.packageName = packageName;
            this.launchActivity = launchActivity;
            this.playing = shouldPlay;
            this.completion = completion;
            this.manager = (MediaSessionManager) context.getSystemService(
                    Context.MEDIA_SESSION_SERVICE);
        }

        void start() {
            if (!playing) {
                logEvent(context, "mediaPlaySkipped",
                        "Snapshot state is not playing: " + packageName);
                if (launchActivity) launchSourceActivity(context, packageName);
                complete(Result.SKIPPED);
                return;
            }

            if (manager == null) {
                logEvent(context, "mediaRestoreFailed", "MediaSessionManager unavailable");
                if (launchActivity) launchSourceActivity(context, packageName);
                complete(Result.FAILED);
                return;
            }

            logEvent(context, "mediaRestoreWaiting",
                    "Waiting for MediaSession: " + packageName);
            if (!handler.postDelayed(timeout, MEDIA_TIMEOUT_MS)) {
                logEvent(context, "mediaRestoreFailed",
                        "Unable to schedule MediaSession timeout");
                complete(Result.FAILED);
                return;
            }

            registerListener();
            if (!isActive()) {
                if (launchActivity) launchSourceActivity(context, packageName);
                return;
            }

            if (launchActivity) {
                logEvent(context, "mediaActivityLaunch", "Launching " + packageName);
                launchSourceActivity(context, packageName);
                return;
            }

            MediaController existing = findStandardSession(getActiveSessions());
            if (existing != null) {
                logEvent(context, "mediaSessionFound", "Existing session: " + packageName);
                resume(existing);
            }
        }

        private boolean isActive() {
            return state.get() == State.ACTIVE;
        }

        private List<MediaController> getActiveSessions() {
            try {
                return manager.getActiveSessions(null);
            } catch (RuntimeException error) {
                logEvent(context, "mediaRestoreFailed",
                        "Unable to query active sessions: " + error);
                return null;
            }
        }

        private MediaController findStandardSession(List<MediaController> sessions) {
            if (sessions == null) return null;
            for (MediaController controller : sessions) {
                if (controller != null && packageName.equals(controller.getPackageName())) {
                    return controller;
                }
            }
            return null;
        }

        private void registerListener() {
            try {
                manager.addOnActiveSessionsChangedListener(listener, null, handler);
                synchronized (lock) {
                    if (state.get() == State.ACTIVE) {
                        listenerRegistered = true;
                    } else {
                        manager.removeOnActiveSessionsChangedListener(listener);
                    }
                }
                logEvent(context, "mediaSessionListenerRegistered", "Source: " + packageName);
            } catch (RuntimeException error) {
                logEvent(context, "mediaSessionListenerFailed", error.toString());
                complete(Result.FAILED);
            }
        }

        private void onSessionsChanged(List<MediaController> sessions) {
            if (!isActive()) return;
            MediaController controller = findStandardSession(sessions);
            if (controller != null) {
                logEvent(context, "mediaSessionFound", "Active session: " + packageName);
                resume(controller);
            }
        }

        private void resume(MediaController controller) {
            if (controller == null || !isActive()) return;

            try {
                PlaybackState current = controller.getPlaybackState();
                if (isPlaying(current)) {
                    logEvent(context, "mediaRestoreSkipped",
                            "Playback was already active before PLAY: " + packageName);
                    complete(Result.SKIPPED);
                    return;
                }

                synchronized (lock) {
                    if (state.get() != State.ACTIVE) return;
                    activeController = controller;
                    state.set(State.PLAY_SENT);
                }

                controller.registerCallback(playbackCallback, handler);
                boolean callbackActive;
                synchronized (lock) {
                    callbackActive = state.get() == State.ACTIVE;
                    if (callbackActive) controllerCallbackRegistered = true;
                }
                if (!callbackActive) {
                    try {
                        controller.unregisterCallback(playbackCallback);
                    } catch (RuntimeException ignored) {
                    }
                    return;
                }

                current = controller.getPlaybackState();
                if (isPlaying(current)) {
                    logEvent(context, "mediaRestoreSkipped",
                            "Playback became active before PLAY: " + packageName);
                    complete(Result.SKIPPED);
                    return;
                }

                handler.removeCallbacks(timeout);
                if (!handler.postDelayed(timeout, MEDIA_TIMEOUT_MS)) {
                    logEvent(context, "mediaRestoreFailed",
                            "Unable to schedule playback confirmation");
                    complete(Result.FAILED);
                    return;
                }

                playRequested = true;
                controller.getTransportControls().play();
                logEvent(context, "mediaPlaySent",
                        "TransportControls.play(): " + packageName);
            } catch (RuntimeException error) {
                logEvent(context, "mediaPlayFailed", error.toString());
                complete(Result.FAILED);
            }
        }

        private boolean isPlaying(PlaybackState playbackState) {
            return playbackState != null
                    && playbackState.getState() == PlaybackState.STATE_PLAYING;
        }

        private void onTimeout() {
            State current = state.get();
            if (current == State.ACTIVE) {
                logEvent(context, "mediaRestoreTimeout",
                        "5-second MediaSession wait expired: " + packageName);
            } else if (current == State.PLAY_SENT) {
                logEvent(context, "mediaRestoreTimeout",
                        "Playback was not confirmed within 5 seconds: " + packageName);
            }
            complete(Result.FAILED);
        }

        private void complete(Result result) {
            boolean completed;
            synchronized (lock) {
                State current = state.get();
                if (current == State.COMPLETED || current == State.CANCELLED) return;
                if (result == Result.RESUMED && (!playRequested || current != State.PLAY_SENT)) {
                    return;
                }
                state.set(State.COMPLETED);
                completed = true;
            }
            if (!completed) return;
            cleanup();
            notifyCompletion(completion, result);
        }

        private void cleanup() {
            handler.removeCallbacks(timeout);
            boolean removeListener;
            boolean removeControllerCallback;
            MediaController controller;
            synchronized (lock) {
                removeListener = listenerRegistered;
                listenerRegistered = false;
                removeControllerCallback = controllerCallbackRegistered;
                controllerCallbackRegistered = false;
                controller = activeController;
                activeController = null;
            }
            if (removeListener) {
                try {
                    manager.removeOnActiveSessionsChangedListener(listener);
                } catch (RuntimeException ignored) {
                }
            }
            if (removeControllerCallback && controller != null) {
                try {
                    controller.unregisterCallback(playbackCallback);
                } catch (RuntimeException ignored) {
                }
            }
        }

        @Override
        public void cancel() {
            boolean cancelled = false;
            synchronized (lock) {
                State current = state.get();
                if (current == State.ACTIVE || current == State.PLAY_SENT) {
                    state.set(State.CANCELLED);
                    cancelled = true;
                }
            }
            if (cancelled) {
                cleanup();
                notifyCompletion(completion, Result.CANCELLED);
                logEvent(context, "mediaRestoreCancelled",
                        "Restoration cancelled: " + packageName);
            }
        }
    }

    private static final class BluetoothRestore implements Cancellable {
        private enum State {
            ACTIVE,
            PLAY_SENT,
            COMPLETED,
            CANCELLED
        }

        private static final int TRANSACTION_REGISTER_CALLBACK = 10;
        private static final int TRANSACTION_UNREGISTER_CALLBACK = 11;
        private static final int CALLBACK_A2DP_STATE = 1;
        private static final int CALLBACK_AVRCP_STATE = 2;
        private static final int CALLBACK_METADATA = 3;
        private static final int CALLBACK_PLAY_STATE = 4;
        private static final int BLUETOOTH_STATE_CONNECTED = 2;
        private static final String CALLBACK_DESCRIPTOR =
                "com.hcn.bluetooth.service.IMusicCallback";
        private static final long PLAY_DELAY_MS = 1000L;

        private final Context context;
        private final String packageName;
        private final boolean launchActivity;
        private final Completion completion;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final MediaSessionManager manager;
        private final AtomicReference<State> state =
                new AtomicReference<>(State.ACTIVE);
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final Object lock = new Object();
        private long deadlineElapsedRealtime;

        private IBinder binder;
        private boolean sessionReady;
        private boolean workerSubmitted;
        private boolean bound;
        private boolean listenerRegistered;
        private boolean callbackRegistrationStarted;
        private boolean callbackRegistered;
        private boolean callbacksReady;
        private boolean a2dpConnected;
        private boolean avrcpConnected;
        private boolean playDelayScheduled;
        private boolean playTaskSubmitted;
        private boolean playCommitted;
        private long playDispatchElapsedRealtime;
        private volatile boolean playRequested;
        private boolean controllerCallbackRegistered;
        private MediaController vendorController;
        private int profileStateVersion;
        private int a2dpCallbackVersion;
        private int avrcpCallbackVersion;
        private int a2dpCallbackVersionAtRegistration;
        private int avrcpCallbackVersionAtRegistration;

        private final MusicCallback callback = new MusicCallback();
        private final MediaSessionManager.OnActiveSessionsChangedListener listener =
                this::onSessionsChanged;
        private final Runnable timeout = this::onTimeout;
        private final Runnable playDelay = this::onPlayDelayElapsed;
        private final MediaController.Callback playbackCallback =
                new MediaController.Callback() {
                    @Override
                    public void onPlaybackStateChanged(PlaybackState playbackState) {
                        if (playbackState == null) return;
                        if (playbackState.getState() == PlaybackState.STATE_PLAYING) {
                            long updateTime = playbackState.getLastPositionUpdateTime();
                            boolean startedBeforePlay;
                            synchronized (lock) {
                                State current = state.get();
                                startedBeforePlay = current == State.ACTIVE
                                        || (current == State.PLAY_SENT
                                        && updateTime <= playDispatchElapsedRealtime);
                            }
                            complete(startedBeforePlay ? Result.SKIPPED : Result.RESUMED);
                        } else if (playbackState.getState() == PlaybackState.STATE_ERROR
                                && playRequested) {
                            complete(Result.FAILED);
                        }
                    }
                };
        private final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (service == null) {
                    fail("Vendor service returned a null Binder");
                    return;
                }
                synchronized (lock) {
                    if (state.get() != State.ACTIVE) return;
                    binder = service;
                }
                startWorkerIfReady();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                fail("Vendor service disconnected");
            }

            @Override
            public void onBindingDied(ComponentName name) {
                fail("Vendor service binding died");
            }

            @Override
            public void onNullBinding(ComponentName name) {
                fail("Vendor service returned a null binding");
            }
        };

        BluetoothRestore(Context context, String packageName, boolean launchActivity,
                         Completion completion) {
            this.context = context;
            this.packageName = packageName;
            this.launchActivity = launchActivity;
            this.completion = completion;
            this.manager = (MediaSessionManager) context.getSystemService(
                    Context.MEDIA_SESSION_SERVICE);
        }

        void start() {
            logEvent(context, "bluetoothRestoreStarted",
                    "Waiting for vendor MediaSession and Binder service");
            if (manager == null) {
                if (launchActivity) launchSourceActivity(context, packageName);
                fail("MediaSessionManager unavailable");
                return;
            }

            deadlineElapsedRealtime = SystemClock.elapsedRealtime() + BLUETOOTH_TIMEOUT_MS;
            if (!handler.postDelayed(timeout, BLUETOOTH_TIMEOUT_MS)) {
                fail("Unable to schedule Bluetooth restoration timeout");
                return;
            }

            registerListener();
            if (state.get() != State.ACTIVE) {
                if (launchActivity) launchSourceActivity(context, packageName);
                return;
            }

            if (launchActivity) {
                launchSourceActivity(context, packageName);
            }

            if (state.get() != State.ACTIVE) return;
            MediaController existingSession = findVendorSession(getActiveSessions());
            if (existingSession != null) {
                markSessionReady(existingSession, "Existing vendor session");
            }

            if (state.get() != State.ACTIVE) return;
            bindService();
            startWorkerIfReady();
        }

        private List<MediaController> getActiveSessions() {
            try {
                return manager.getActiveSessions(null);
            } catch (RuntimeException error) {
                logEvent(context, "bluetoothSessionLookupFailed", error.toString());
                return null;
            }
        }

        private MediaController findVendorSession(List<MediaController> sessions) {
            if (sessions == null) return null;
            for (MediaController controller : sessions) {
                if (isVendorSession(controller)) return controller;
            }
            return null;
        }

        private void registerListener() {
            try {
                manager.addOnActiveSessionsChangedListener(listener, null, handler);
                synchronized (lock) {
                    if (state.get() == State.ACTIVE) {
                        listenerRegistered = true;
                    } else {
                        manager.removeOnActiveSessionsChangedListener(listener);
                    }
                }
                logEvent(context, "bluetoothSessionListenerRegistered",
                        "Waiting for vendor MediaSession");
            } catch (RuntimeException error) {
                fail("Unable to register MediaSession listener: " + error);
            }
        }

        private void onSessionsChanged(List<MediaController> sessions) {
            MediaController controller = findVendorSession(sessions);
            if (controller != null) {
                markSessionReady(controller, "Vendor MediaSession became active");
            }
        }

        private void markSessionReady(MediaController controller, String message) {
            boolean removeListener;
            synchronized (lock) {
                if (state.get() != State.ACTIVE || sessionReady) return;
                sessionReady = true;
                vendorController = controller;
                removeListener = listenerRegistered;
                listenerRegistered = false;
            }

            if (removeListener) removeSessionListener();
            logEvent(context, "bluetoothMediaSessionFound", message);
            startWorkerIfReady();
        }

        private void removeSessionListener() {
            try {
                manager.removeOnActiveSessionsChangedListener(listener);
                logEvent(context, "bluetoothSessionListenerUnregistered",
                        "MediaSession listener removed");
            } catch (RuntimeException error) {
                logEvent(context, "bluetoothSessionListenerUnregisterFailed",
                        error.toString());
            }
        }

        private void bindService() {
            Intent intent = new Intent(BLUETOOTH_SERVICE_ACTION);
            intent.setComponent(new ComponentName(
                    BLUETOOTH_SERVICE_PACKAGE, BLUETOOTH_SERVICE_CLASS));
            try {
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException error) {
                bound = false;
                logEvent(context, "bluetoothBindFailed", error.toString());
            }
            if (!bound) {
                fail("Unable to bind vendor service");
            } else {
                logEvent(context, "bluetoothServiceBound",
                        "Vendor service binding requested");
            }
        }

        private void startWorkerIfReady() {
            final IBinder service;
            synchronized (lock) {
                if (state.get() != State.ACTIVE || binder == null
                        || !sessionReady || workerSubmitted) return;
                service = binder;
                workerSubmitted = true;
            }

            if (!BLUETOOTH_WORKER_BUSY.compareAndSet(false, true)) {
                fail("Previous Bluetooth Binder operation has not returned");
                return;
            }

            try {
                BLUETOOTH_WORKER.execute(() -> {
                    try {
                        initializeBluetooth(service);
                    } catch (RemoteException | RuntimeException error) {
                        fail("Bluetooth initialization failed: " + error);
                    } finally {
                        BLUETOOTH_WORKER_BUSY.set(false);
                    }
                });
            } catch (RuntimeException error) {
                BLUETOOTH_WORKER_BUSY.set(false);
                fail("Unable to start Bluetooth Binder worker: " + error);
            }
        }

        private void initializeBluetooth(IBinder service) throws RemoteException {
            if (state.get() != State.ACTIVE) return;
            registerCallback(service);
            if (state.get() != State.ACTIVE) return;
            queryInitialProfileStates(service);
            if (state.get() != State.ACTIVE) return;
            if (!handler.post(this::onCallbacksReady)) {
                fail("Unable to dispatch Bluetooth callback readiness");
            }
        }

        private void queryInitialProfileStates(IBinder service) {
            queryInitialProfileState(service, true, 1,
                    a2dpCallbackVersionAtRegistration);
            queryInitialProfileState(service, false, 2,
                    avrcpCallbackVersionAtRegistration);
        }

        private void queryInitialProfileState(IBinder service, boolean a2dp,
                                              int transaction, int callbackVersionAtRegistration) {
            final boolean connected;
            try {
                connected = queryBoolean(service, transaction);
            } catch (RemoteException | RuntimeException error) {
                logEvent(context, a2dp ? "bluetoothA2dpInitialStateFailed"
                                : "bluetoothAvrcpInitialStateFailed",
                        error.toString());
                return;
            }

            synchronized (lock) {
                if (state.get() != State.ACTIVE) return;
                int callbackVersion = a2dp
                        ? a2dpCallbackVersion : avrcpCallbackVersion;
                if (callbackVersion != callbackVersionAtRegistration) return;

                if (a2dp) {
                    a2dpConnected = connected;
                } else {
                    avrcpConnected = connected;
                }
                profileStateVersion++;
            }

            logEvent(context, a2dp ? "bluetoothA2dpInitialState"
                            : "bluetoothAvrcpInitialState",
                    connected ? "connected" : "disconnected");
        }

        private void registerCallback(IBinder service) throws RemoteException {
            synchronized (lock) {
                if (state.get() != State.ACTIVE) return;
                callbackRegistrationStarted = true;
                a2dpCallbackVersionAtRegistration = a2dpCallbackVersion;
                avrcpCallbackVersionAtRegistration = avrcpCallbackVersion;
            }

            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(BLUETOOTH_DESCRIPTOR);
                data.writeStrongBinder(callback.asBinder());
                if (!service.transact(TRANSACTION_REGISTER_CALLBACK, data, reply, 0)) {
                    throw new RemoteException("Bluetooth callback registration failed");
                }
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }

            boolean active;
            synchronized (lock) {
                callbackRegistered = true;
                active = state.get() == State.ACTIVE;
            }
            if (!active) {
                unregisterCallback(service);
                return;
            }
            logEvent(context, "bluetoothCallbackRegistered",
                    "Waiting for A2DP and AVRCP state callbacks");
        }

        private void onCallbacksReady() {
            synchronized (lock) {
                if (state.get() != State.ACTIVE || !callbackRegistered) return;
                callbacksReady = true;
            }
            logEvent(context, "bluetoothCallbacksReady",
                    "Vendor callbacks registered; waiting for profile events");
            updatePlayDelay();
        }

        private void onProfileStateChanged(boolean a2dp, int profileState) {
            boolean connected = profileState == BLUETOOTH_STATE_CONNECTED;
            synchronized (lock) {
                if (state.get() != State.ACTIVE) return;

                profileStateVersion++;
                if (a2dp) {
                    a2dpCallbackVersion++;
                    a2dpConnected = connected;
                } else {
                    avrcpCallbackVersion++;
                    avrcpConnected = connected;
                }

                if (!connected && playDelayScheduled) {
                    handler.removeCallbacks(playDelay);
                    playDelayScheduled = false;
                }
            }

            String connectionState;
            switch (profileState) {
                case 1:
                    connectionState = "connecting";
                    break;
                case BLUETOOTH_STATE_CONNECTED:
                    connectionState = "connected";
                    break;
                default:
                    connectionState = "disconnected";
                    break;
            }
            logEvent(context, a2dp ? "bluetoothA2dpStateChanged"
                            : "bluetoothAvrcpStateChanged",
                    connectionState);
            handler.post(this::updatePlayDelay);
        }

        private void updatePlayDelay() {
            boolean schedule = false;
            synchronized (lock) {
                if (state.get() != State.ACTIVE || !callbacksReady
                        || !a2dpConnected || !avrcpConnected
                        || playDelayScheduled || playTaskSubmitted) {
                    return;
                }
                playDelayScheduled = true;
                if (!handler.postDelayed(playDelay, PLAY_DELAY_MS)) {
                    playDelayScheduled = false;
                    schedule = false;
                } else {
                    schedule = true;
                }
            }

            if (schedule) {
                logEvent(context, "bluetoothProfilesReady",
                        "A2DP and AVRCP connected; waiting 1 second before PLAY");
            } else {
                fail("Unable to schedule Bluetooth PLAY delay");
            }
        }

        private void onPlayDelayElapsed() {
            if (!preparePlaybackConfirmation()) return;

            final IBinder service;
            final int connectionVersion;
            synchronized (lock) {
                playDelayScheduled = false;
                if (state.get() != State.ACTIVE || !callbacksReady
                        || !a2dpConnected || !avrcpConnected || binder == null
                        || playTaskSubmitted) {
                    return;
                }
                if (!BLUETOOTH_WORKER_BUSY.compareAndSet(false, true)) {
                    service = null;
                    connectionVersion = -1;
                } else {
                    playTaskSubmitted = true;
                    service = binder;
                    connectionVersion = profileStateVersion;
                }
            }

            if (service == null) {
                fail("Bluetooth Binder worker is busy before PLAY");
                return;
            }

            try {
                BLUETOOTH_WORKER.execute(() -> {
                    boolean expired = false;
                    boolean committed;
                    synchronized (lock) {
                        if (state.get() == State.ACTIVE && a2dpConnected && avrcpConnected
                                && connectionVersion == profileStateVersion
                                && SystemClock.elapsedRealtime() >= deadlineElapsedRealtime) {
                            expired = state.compareAndSet(State.ACTIVE, State.CANCELLED);
                        }

                        committed = !expired && state.get() == State.ACTIVE
                                && a2dpConnected && avrcpConnected
                                && connectionVersion == profileStateVersion
                                && state.compareAndSet(State.ACTIVE, State.PLAY_SENT);
                        if (committed) {
                            playCommitted = true;
                            playRequested = true;
                            playDispatchElapsedRealtime = Long.MAX_VALUE;
                        } else {
                            playTaskSubmitted = false;
                        }
                    }

                    if (!committed) {
                        BLUETOOTH_WORKER_BUSY.set(false);
                        if (expired) {
                            long elapsedMs = SystemClock.elapsedRealtime()
                                    - (deadlineElapsedRealtime - BLUETOOTH_TIMEOUT_MS);
                            logEvent(context, "bluetoothRestoreTimeout",
                                    "30-second deadline reached before PLAY commit after "
                                            + elapsedMs + " ms");
                            finishResources(Result.FAILED);
                        } else {
                            handler.post(this::updatePlayDelay);
                        }
                        return;
                    }

                    try {
                        if (sendPlay(service)) {
                            logEvent(context, "bluetoothPlaySent",
                                    "send_Avrcp_Cmd(PLAY) transaction completed");
                        } else {
                            logEvent(context, "bluetoothPlaySkipped",
                                    "Playback became active before PLAY dispatch");
                        }
                    } catch (RemoteException | RuntimeException error) {
                        logEvent(context, "bluetoothPlayFailed", error.toString());
                        fail("Bluetooth PLAY command failed: " + error);
                    } finally {
                        BLUETOOTH_WORKER_BUSY.set(false);
                    }
                });
            } catch (RuntimeException error) {
                synchronized (lock) {
                    playTaskSubmitted = false;
                }
                BLUETOOTH_WORKER_BUSY.set(false);
                fail("Unable to send Bluetooth PLAY: " + error);
            }
        }

        private boolean queryBoolean(IBinder service, int transaction)
                throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(BLUETOOTH_DESCRIPTOR);
                if (!service.transact(transaction, data, reply, 0)) {
                    throw new RemoteException("Unsupported Bluetooth transaction " + transaction);
                }
                reply.readException();
                return reply.readInt() != 0;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private boolean sendPlay(IBinder service) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(BLUETOOTH_DESCRIPTOR);
                data.writeInt(0);
                synchronized (lock) {
                    if (state.get() != State.PLAY_SENT) return false;
                    playDispatchElapsedRealtime = SystemClock.elapsedRealtime();
                }
                if (!service.transact(TRANSACTION_SEND_AVRCP_COMMAND, data, reply, 0)) {
                    throw new RemoteException("Bluetooth PLAY transaction failed");
                }
                reply.readException();
                return true;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private void unregisterCallback(IBinder service) {
            synchronized (lock) {
                if (!callbackRegistered) return;
                callbackRegistered = false;
            }

            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(BLUETOOTH_DESCRIPTOR);
                data.writeStrongBinder(callback.asBinder());
                if (!service.transact(TRANSACTION_UNREGISTER_CALLBACK, data, reply, 0)) {
                    throw new RemoteException("Bluetooth callback unregistration failed");
                }
                reply.readException();
                logEvent(context, "bluetoothCallbackUnregistered", "true");
            } catch (RemoteException | RuntimeException error) {
                logEvent(context, "bluetoothCallbackUnregistered", "false");
                logEvent(context, "bluetoothCallbackUnregisterFailed", error.toString());
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private void onTimeout() {
            boolean timedOut = false;
            boolean committed;
            String readiness;
            synchronized (lock) {
                committed = playCommitted;
                State current = state.get();
                if (current == State.ACTIVE || current == State.PLAY_SENT) {
                    state.set(State.COMPLETED);
                    timedOut = true;
                }
                readiness = "MediaSession: " + sessionReady
                        + ", Binder: " + (binder != null)
                        + ", callbacksReady: " + callbacksReady
                        + ", A2DP: " + a2dpConnected
                        + ", AVRCP: " + avrcpConnected;
            }
            if (timedOut) {
                long elapsedMs = SystemClock.elapsedRealtime()
                        - (deadlineElapsedRealtime - BLUETOOTH_TIMEOUT_MS);
                logEvent(context, "bluetoothRestoreTimeout",
                        "30-second restoration deadline reached after "
                                + elapsedMs + " ms; PLAY committed: "
                                + committed + "; " + readiness);
                finishResources(Result.FAILED);
            }
        }

        private void fail(String reason) {
            boolean failed = false;
            synchronized (lock) {
                State current = state.get();
                if (current == State.ACTIVE || current == State.PLAY_SENT) {
                    state.set(State.COMPLETED);
                    failed = true;
                }
            }
            if (failed) {
                logEvent(context, "bluetoothRestoreFailed", reason);
                finishResources(Result.FAILED);
            }
        }

        private void complete(Result result) {
            boolean completed = false;
            synchronized (lock) {
                State current = state.get();
                if (current == State.COMPLETED || current == State.CANCELLED) return;
                if (result == Result.RESUMED
                        && (!playRequested || current != State.PLAY_SENT)) return;
                state.set(State.COMPLETED);
                completed = true;
            }
            if (completed) finishResources(result);
        }

        private boolean preparePlaybackConfirmation() {
            MediaController controller;
            synchronized (lock) {
                if (state.get() != State.ACTIVE) return false;
                controller = vendorController;
                if (controllerCallbackRegistered) return true;
            }
            if (controller == null) {
                fail("Bluetooth MediaSession unavailable for playback confirmation");
                return false;
            }

            try {
                PlaybackState current = controller.getPlaybackState();
                if (current != null && current.getState() == PlaybackState.STATE_PLAYING) {
                    logEvent(context, "bluetoothRestoreSkipped",
                            "Playback was already active before PLAY");
                    complete(Result.SKIPPED);
                    return false;
                }

                controller.registerCallback(playbackCallback, handler);
                synchronized (lock) {
                    if (state.get() != State.ACTIVE) {
                        controller.unregisterCallback(playbackCallback);
                        return false;
                    }
                    controllerCallbackRegistered = true;
                }

                current = controller.getPlaybackState();
                if (current != null && current.getState() == PlaybackState.STATE_PLAYING) {
                    logEvent(context, "bluetoothRestoreSkipped",
                            "Playback became active before PLAY");
                    complete(Result.SKIPPED);
                    return false;
                }
                return true;
            } catch (RuntimeException error) {
                fail("Unable to monitor Bluetooth playback state: " + error);
                return false;
            }
        }

        private void finishResources(Result result) {
            if (!cleaned.compareAndSet(false, true)) return;
            handler.removeCallbacks(timeout);
            handler.removeCallbacks(playDelay);

            IBinder service;
            boolean removeListener;
            boolean unregister;
            boolean unregisterController;
            MediaController controller;
            boolean unbindService;
            synchronized (lock) {
                playDelayScheduled = false;
                playTaskSubmitted = false;
                removeListener = listenerRegistered;
                listenerRegistered = false;
                unregister = callbackRegistrationStarted;
                unregisterController = controllerCallbackRegistered;
                controllerCallbackRegistered = false;
                controller = vendorController;
                vendorController = null;
                service = binder;
                unbindService = bound;
                bound = false;
                binder = null;
            }

            if (unbindService) {
                try {
                    context.unbindService(connection);
                    logEvent(context, "bluetoothServiceUnbound", "true");
                } catch (IllegalArgumentException error) {
                    logEvent(context, "bluetoothServiceUnbound", "false");
                    logEvent(context, "bluetoothServiceUnbindFailed",
                            error.toString());
                }
            }

            if (removeListener) removeSessionListener();
            if (unregisterController && controller != null) {
                try {
                    controller.unregisterCallback(playbackCallback);
                } catch (RuntimeException ignored) {
                }
            }

            if (unregister && service != null) {
                try {
                    BLUETOOTH_WORKER.execute(() -> unregisterCallback(service));
                } catch (RuntimeException error) {
                    logEvent(context, "bluetoothCallbackUnregisterFailed",
                            "Unable to schedule callback cleanup: " + error);
                }
            }

            notifyCompletion(completion, result);
        }

        @Override
        public void cancel() {
            boolean cancelled = false;
            synchronized (lock) {
                State current = state.get();
                if (current == State.ACTIVE || current == State.PLAY_SENT) {
                    state.set(State.CANCELLED);
                    cancelled = true;
                }
            }
            if (cancelled) {
                logEvent(context, "bluetoothRestoreCancelled", "Restoration cancelled");
                finishResources(Result.CANCELLED);
            }
        }

        private final class MusicCallback extends Binder implements IInterface {
            MusicCallback() {
                attachInterface(this, CALLBACK_DESCRIPTOR);
            }

            @Override
            public IBinder asBinder() {
                return this;
            }

            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == 1598968902) {
                    if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                    return true;
                }

                data.enforceInterface(CALLBACK_DESCRIPTOR);
                switch (code) {
                    case CALLBACK_A2DP_STATE:
                        onProfileStateChanged(true, data.readInt());
                        return true;
                    case CALLBACK_AVRCP_STATE:
                        onProfileStateChanged(false, data.readInt());
                        return true;
                    case CALLBACK_METADATA:
                        data.readString();
                        data.readString();
                        data.readString();
                        return true;
                    case CALLBACK_PLAY_STATE:
                        data.readInt();
                        if (reply != null) reply.writeNoException();
                        return true;
                    default:
                        return super.onTransact(code, data, reply, flags);
                }
            }
        }
    }

    private static void launchSourceActivity(Context context, String packageName) {
        try {
            Intent intent;
            if (BLUETOOTH_PACKAGE.equals(packageName)) {
                intent = new Intent();
                intent.setComponent(new ComponentName(packageName, BLUETOOTH_ACTIVITY));
            } else {
                PackageManager packageManager = context.getPackageManager();
                intent = packageManager.getLaunchIntentForPackage(packageName);
                if (intent == null) {
                    logEvent(context, "mediaActivityLaunchFailed",
                            "No launch Activity for " + packageName);
                    return;
                }
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            logEvent(context, "mediaActivityLaunched",
                    "Activity started: " + packageName);
        } catch (RuntimeException error) {
            logEvent(context, "mediaActivityLaunchFailed", error.toString());
        }
    }

    private static void logEvent(Context context, String source, String message) {
        if (context == null) return;
        try {
            EventLogger.log(context, "Media", source, message);
        } catch (RuntimeException ignored) {
        }
    }

    private static void notifyCompletion(Completion completion, Result result) {
        if (completion == null) return;
        try {
            completion.onComplete(result);
        } catch (RuntimeException ignored) {
        }
    }
}
