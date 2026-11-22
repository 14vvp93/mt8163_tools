package com.mt8163.tools.modules.media;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

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
    private static final String SESSION_TAG_A2DP = "A2dpMediaBrowserService";
    private static final String SESSION_TAG_BLUETOOTH = "BluetoothMediaBrowserService";
    private static final int TRANSACTION_IS_A2DP_CONNECTED = 1;
    private static final int TRANSACTION_IS_AVRCP_CONNECTED = 2;
    private static final int TRANSACTION_SEND_AVRCP_COMMAND = 5;
    private static final long MEDIA_TIMEOUT_MS = 5000L;
    private static final long BLUETOOTH_TIMEOUT_MS = 10000L;

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

    public interface Completion {
        void onComplete();
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
            if (completion != null) completion.onComplete();
            return () -> { };
        }

        boolean launchActivity = isForeground
                || (BLUETOOTH_PACKAGE.equals(packageName)
                ? restoreBackgroundBluetooth : restoreBackgroundMedia);

        logEvent(appContext, "restoreRequested", "Source: " + packageName
                + ", playback: " + state + ", foreground: " + isForeground
                + ", launchActivity: " + launchActivity);

        if (BLUETOOTH_PACKAGE.equals(packageName)) {
            if (!isPlaying(state)) {
                logEvent(appContext, "bluetoothRestoreSkipped", "Playback state is " + state);
                if (launchActivity) launchBluetoothActivity(appContext);
                notifyCompletion(completion);
                return () -> { };
            }
            BluetoothRestore restore = new BluetoothRestore(
                    appContext, launchActivity, completion);
            restore.start();
            return restore;
        }

        StandardRestore restore = new StandardRestore(
                appContext, packageName, launchActivity, state, completion);
        restore.start();
        return restore;
    }

    private static boolean isPlaying(String state) {
        return MediaSettingsStore.PLAYBACK_PLAYING.equals(
                MediaSettingsStore.normalizePlaybackState(state));
    }

    private static boolean isVendorSession(MediaController controller) {
        if (controller == null) return false;
        String tag = controller.getTag();
        return SESSION_TAG_A2DP.equals(tag) || SESSION_TAG_BLUETOOTH.equals(tag);
    }

    private static final class StandardRestore implements Cancellable {
        private enum State {
            ACTIVE,
            PLAY_COMMITTED,
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

        private final MediaSessionManager.OnActiveSessionsChangedListener listener =
                this::onSessionsChanged;
        private final Runnable timeout = this::onTimeout;

        StandardRestore(Context context, String packageName, boolean launchActivity,
                        String state, Completion completion) {
            this.context = context;
            this.packageName = packageName;
            this.launchActivity = launchActivity;
            this.playing = isPlaying(state);
            this.completion = completion;
            this.manager = (MediaSessionManager) context.getSystemService(
                    Context.MEDIA_SESSION_SERVICE);
        }

        void start() {
            if (!playing) {
                logEvent(context, "mediaPlaySkipped",
                        "Snapshot state is not playing: " + packageName);
                if (launchActivity) launchPackageActivity(context, packageName);
                completeWithoutPlay();
                return;
            }

            if (manager == null) {
                logEvent(context, "mediaRestoreFailed", "MediaSessionManager unavailable");
                if (launchActivity) launchPackageActivity(context, packageName);
                completeWithoutPlay();
                return;
            }

            logEvent(context, "mediaRestoreWaiting",
                    "Waiting for MediaSession: " + packageName);
            if (!handler.postDelayed(timeout, MEDIA_TIMEOUT_MS)) {
                logEvent(context, "mediaRestoreFailed", "Unable to schedule MediaSession timeout");
                completeWithoutPlay();
                return;
            }

            registerListener();
            if (!isActive()) {
                if (launchActivity) launchPackageActivity(context, packageName);
                return;
            }

            if (launchActivity) {
                logEvent(context, "mediaActivityLaunch", "Launching " + packageName);
                launchPackageActivity(context, packageName);
                return;
            }

            MediaController existing = findStandardSession(getActiveSessions());
            if (existing != null) {
                logEvent(context, "mediaSessionFound", "Existing session: " + packageName);
                finish(existing);
            }
        }

        private boolean isActive() {
            synchronized (lock) {
                return state.get() == State.ACTIVE;
            }
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
                completeWithoutPlay();
            }
        }

        private void onSessionsChanged(List<MediaController> sessions) {
            if (!isActive()) return;
            MediaController controller = findStandardSession(sessions);
            if (controller != null) {
                logEvent(context, "mediaSessionFound", "Active session: " + packageName);
                finish(controller);
            }
        }

        private void onTimeout() {
            boolean completed = false;
            synchronized (lock) {
                if (state.compareAndSet(State.ACTIVE, State.CANCELLED)) {
                    cleanupListenerLocked();
                    completed = true;
                }
            }
            if (completed) {
                logEvent(context, "mediaRestoreTimeout",
                        "5-second MediaSession wait expired: " + packageName);
                notifyCompletion(completion);
            }
        }

        private void completeWithoutPlay() {
            boolean completed = false;
            synchronized (lock) {
                if (state.compareAndSet(State.ACTIVE, State.COMPLETED)) {
                    handler.removeCallbacks(timeout);
                    cleanupListenerLocked();
                    completed = true;
                }
            }
            if (completed) notifyCompletion(completion);
        }

        private void finish(MediaController controller) {
            boolean completed = false;
            if (controller != null && playing) {
                if (!state.compareAndSet(State.ACTIVE, State.PLAY_COMMITTED)) return;
                handler.removeCallbacks(timeout);
                cleanupListenerLocked();
                try {
                    controller.getTransportControls().play();
                    logEvent(context, "mediaPlaySent",
                            "TransportControls.play(): " + packageName);
                } catch (RuntimeException error) {
                    logEvent(context, "mediaPlayFailed", error.toString());
                }
                state.compareAndSet(State.PLAY_COMMITTED, State.COMPLETED);
                completed = true;
            } else if (state.compareAndSet(State.ACTIVE, State.COMPLETED)) {
                handler.removeCallbacks(timeout);
                cleanupListenerLocked();
                logEvent(context, "mediaRestoreEnded",
                        "No matching MediaSession; PLAY not sent");
                completed = true;
            }
            if (completed) notifyCompletion(completion);
        }

        private void cleanupListenerLocked() {
            synchronized (lock) {
                if (!listenerRegistered) return;
                listenerRegistered = false;
            }
            try {
                manager.removeOnActiveSessionsChangedListener(listener);
            } catch (RuntimeException ignored) {
            }
        }

        @Override
        public void cancel() {
            boolean cancelled = false;
            synchronized (lock) {
                if (state.compareAndSet(State.ACTIVE, State.CANCELLED)) {
                    handler.removeCallbacks(timeout);
                    cleanupListenerLocked();
                    cancelled = true;
                }
            }
            if (cancelled) {
                logEvent(context, "mediaRestoreCancelled",
                        "Restoration cancelled: " + packageName);
                notifyCompletion(completion);
            } else if (state.get() == State.PLAY_COMMITTED) {
                logEvent(context, "mediaRestoreCancelIgnored",
                        "PLAY was already committed: " + packageName);
            }
        }
    }

    private static final class BluetoothRestore implements Cancellable {
        private enum State {
            ACTIVE,
            PLAY_COMMITTED,
            COMPLETED,
            CANCELLED
        }

        private final Context context;
        private final boolean launchActivity;
        private final Completion completion;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final MediaSessionManager manager;
        private final AtomicReference<State> state =
                new AtomicReference<>(State.ACTIVE);
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final Object lock = new Object();

        private IBinder binder;
        private boolean sessionReady;
        private boolean workerSubmitted;
        private boolean bound;
        private boolean listenerRegistered;

        private final MediaSessionManager.OnActiveSessionsChangedListener listener =
                this::onSessionsChanged;
        private final Runnable timeout = this::onTimeout;
        private final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (service == null) {
                    fail();
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
                fail();
            }

            @Override
            public void onBindingDied(ComponentName name) {
                fail();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                fail();
            }
        };

        BluetoothRestore(Context context, boolean launchActivity, Completion completion) {
            this.context = context;
            this.launchActivity = launchActivity;
            this.completion = completion;
            this.manager = (MediaSessionManager) context.getSystemService(
                    Context.MEDIA_SESSION_SERVICE);
        }

        void start() {
            logEvent(context, "bluetoothRestoreStarted", "Waiting for vendor MediaSession and Binder service");
            if (manager == null) {
                if (launchActivity) launchBluetoothActivity(context);
                fail();
                return;
            }

            if (!handler.postDelayed(timeout, BLUETOOTH_TIMEOUT_MS)) {
                fail();
                return;
            }

            if (launchActivity) {
                registerListener();
                if (state.get() != State.ACTIVE) {
                    launchBluetoothActivity(context);
                    return;
                }
                launchBluetoothActivity(context);
            } else {
                registerListener();
                if (state.get() != State.ACTIVE) return;
                boolean existingSession = hasVendorSession(getActiveSessions());
                synchronized (lock) {
                    if (state.get() == State.ACTIVE && existingSession) {
                        sessionReady = true;
                    }
                }
                if (existingSession) {
                    logEvent(context, "bluetoothMediaSessionFound",
                            "Existing vendor session");
                }
            }

            if (state.get() != State.ACTIVE) return;
            bindService();
            startWorkerIfReady();
        }

        private List<MediaController> getActiveSessions() {
            try {
                return manager.getActiveSessions(null);
            } catch (RuntimeException error) {
                return null;
            }
        }

        private boolean hasVendorSession(List<MediaController> sessions) {
            if (sessions == null) return false;
            for (MediaController controller : sessions) {
                if (isVendorSession(controller)) return true;
            }
            return false;
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
                logEvent(context, "bluetoothSessionListenerFailed", error.toString());
                fail();
            }
        }

        private void onSessionsChanged(List<MediaController> sessions) {
            if (state.get() != State.ACTIVE || !hasVendorSession(sessions)) return;
            logEvent(context, "bluetoothMediaSessionFound",
                    "Vendor MediaSession became active");
            synchronized (lock) {
                if (state.get() != State.ACTIVE) return;
                sessionReady = true;
            }
            startWorkerIfReady();
        }

        private void bindService() {
            Intent intent = new Intent(BLUETOOTH_SERVICE_ACTION);
            intent.setComponent(new ComponentName(
                    BLUETOOTH_SERVICE_PACKAGE, BLUETOOTH_SERVICE_CLASS));
            try {
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException error) {
                bound = false;
            }
            if (!bound) {
                logEvent(context, "bluetoothBindFailed", "Unable to bind vendor service");
                fail();
            } else {
                logEvent(context, "bluetoothServiceBound", "Vendor service binding requested");
            }
        }

        private void startWorkerIfReady() {
            final IBinder service;
            synchronized (lock) {
                if (state.get() != State.ACTIVE || binder == null
                        || !sessionReady || workerSubmitted) return;
                service = binder;
                if (!BLUETOOTH_WORKER_BUSY.compareAndSet(false, true)) {
                    workerSubmitted = true;
                    logEvent(context, "bluetoothRestoreBusy",
                            "Previous Bluetooth Binder operation has not returned");
                    fail();
                    return;
                }
                workerSubmitted = true;
            }

            try {
                BLUETOOTH_WORKER.execute(() -> {
                    try {
                        restoreBluetooth(service);
                    } finally {
                        BLUETOOTH_WORKER_BUSY.set(false);
                    }
                });
            } catch (RuntimeException error) {
                BLUETOOTH_WORKER_BUSY.set(false);
                fail();
            }
        }

        private void restoreBluetooth(IBinder service) {
            try {
                if (state.get() != State.ACTIVE) return;
                boolean a2dpConnected = queryBoolean(service, TRANSACTION_IS_A2DP_CONNECTED);
                logEvent(context, "bluetoothA2dpCheck", "Connected: " + a2dpConnected);
                if (!a2dpConnected) {
                    fail();
                    return;
                }

                if (state.get() != State.ACTIVE) return;
                boolean avrcpConnected = queryBoolean(service, TRANSACTION_IS_AVRCP_CONNECTED);
                logEvent(context, "bluetoothAvrcpCheck", "Connected: " + avrcpConnected);
                if (!avrcpConnected) {
                    fail();
                    return;
                }

                if (!state.compareAndSet(State.ACTIVE, State.PLAY_COMMITTED)) return;

                sendPlay(service);
                logEvent(context, "bluetoothPlaySent", "send_Avrcp_Cmd(PLAY) transaction completed");
                state.compareAndSet(State.PLAY_COMMITTED, State.COMPLETED);
                finishResources();
            } catch (RemoteException | RuntimeException error) {
                logEvent(context, "bluetoothRestoreFailed", error.toString());
                fail();
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

        private void sendPlay(IBinder service) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(BLUETOOTH_DESCRIPTOR);
                data.writeInt(0);
                if (!service.transact(TRANSACTION_SEND_AVRCP_COMMAND, data, reply, 0)) {
                    throw new RemoteException("Bluetooth PLAY transaction failed");
                }
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private void onTimeout() {
            if (state.compareAndSet(State.ACTIVE, State.CANCELLED)) {
                logEvent(context, "bluetoothRestoreTimeout",
                        "10-second restoration deadline reached");
                finishResources();
            } else if (state.get() == State.PLAY_COMMITTED) {
                logEvent(context, "bluetoothRestoreTimeout",
                        "Deadline reached after PLAY was committed");
                finishResources();
            }
        }

        private void fail() {
            boolean failed = state.compareAndSet(State.ACTIVE, State.COMPLETED)
                    || state.compareAndSet(State.PLAY_COMMITTED, State.COMPLETED);
            if (failed) {
                logEvent(context, "bluetoothRestoreFailed",
                        "Readiness, connection, or PLAY transaction failed");
                finishResources();
            } else if (state.get() == State.CANCELLED) {
                finishResources();
            }
        }

        private void finishResources() {
            if (!cleaned.compareAndSet(false, true)) return;
            handler.removeCallbacks(timeout);

            boolean removeListener;
            synchronized (lock) {
                removeListener = listenerRegistered;
                listenerRegistered = false;
                if (bound) {
                    bound = false;
                    try {
                        context.unbindService(connection);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                binder = null;
            }
            if (removeListener) {
                try {
                    manager.removeOnActiveSessionsChangedListener(listener);
                } catch (RuntimeException ignored) {
                }
            }
            notifyCompletion(completion);
        }

        @Override
        public void cancel() {
            if (state.compareAndSet(State.ACTIVE, State.CANCELLED)) {
                logEvent(context, "bluetoothRestoreCancelled", "Restoration cancelled");
                finishResources();
            } else if (state.get() == State.PLAY_COMMITTED) {
                logEvent(context, "bluetoothRestoreCancelIgnored",
                        "PLAY transaction was already committed");
            }
        }
    }

    private static void launchPackageActivity(Context context, String packageName) {
        try {
            PackageManager packageManager = context.getPackageManager();
            Intent intent = packageManager.getLaunchIntentForPackage(packageName);
            if (intent == null) {
                logEvent(context, "mediaActivityLaunchFailed", "No launch Activity for " + packageName);
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            logEvent(context, "mediaActivityLaunched", "Activity started: " + packageName);
        } catch (RuntimeException error) {
            logEvent(context, "mediaActivityLaunchFailed", error.toString());
        }
    }

    private static void launchBluetoothActivity(Context context) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(BLUETOOTH_PACKAGE, BLUETOOTH_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            logEvent(context, "bluetoothActivityLaunched", "BtMusicActivity started");
        } catch (RuntimeException error) {
            logEvent(context, "bluetoothActivityLaunchFailed", error.toString());
        }
    }

    private static void logEvent(Context context, String source, String message) {
        if (context == null) return;
        try {
            EventLogger.log(context, "Media", source, message);
        } catch (RuntimeException ignored) {
        }
    }

    private static void notifyCompletion(Completion completion) {
        if (completion == null) return;
        try {
            completion.onComplete();
        } catch (RuntimeException ignored) {
        }
    }
}
