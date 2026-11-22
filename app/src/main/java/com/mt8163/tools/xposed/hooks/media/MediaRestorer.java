package com.mt8163.tools.xposed.hooks.media;

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
        private final Context context;
        private final String packageName;
        private final boolean launchActivity;
        private final boolean playing;
        private final Completion completion;
        private final MediaSessionManager manager;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final AtomicBoolean finished = new AtomicBoolean();

        private boolean listenerRegistered;
        private final MediaSessionManager.OnActiveSessionsChangedListener listener =
                this::onSessionsChanged;
        private final Runnable timeout = () -> {
            logEvent(context, "mediaRestoreTimeout", "5-second MediaSession wait expired: " + packageName);
            finish(null);
        };

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
                logEvent(context, "mediaPlaySkipped", "Snapshot state is not playing: " + packageName);
                if (launchActivity) {
                    logEvent(context, "mediaActivityLaunch", "Launching " + packageName);
                    launchPackageActivity(context, packageName);
                }
                finish(null);
                return;
            }
            if (manager == null) {
                logEvent(context, "mediaRestoreFailed", "MediaSessionManager unavailable");
                if (launchActivity) launchPackageActivity(context, packageName);
                finish(null);
                return;
            }

            logEvent(context, "mediaRestoreWaiting", "Waiting for MediaSession: " + packageName);
            handler.postDelayed(timeout, MEDIA_TIMEOUT_MS);
            if (launchActivity) {
                registerListener();
                if (!finished.get()) {
                    logEvent(context, "mediaActivityLaunch", "Launching " + packageName);
                    launchPackageActivity(context, packageName);
                }
                return;
            }

            MediaController existing = findStandardSession(getActiveSessions());
            if (existing != null) {
                logEvent(context, "mediaSessionFound", "Existing session: " + packageName);
                finish(existing);
                return;
            }
            registerListener();
        }

        private List<MediaController> getActiveSessions() {
            try {
                return manager.getActiveSessions(null);
            } catch (RuntimeException error) {
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
                listenerRegistered = true;
                logEvent(context, "mediaSessionListenerRegistered", "Source: " + packageName);
            } catch (RuntimeException error) {
                logEvent(context, "mediaSessionListenerFailed", error.toString());
                finish(null);
            }
        }

        private void onSessionsChanged(List<MediaController> sessions) {
            if (finished.get()) return;
            MediaController controller = findStandardSession(sessions);
            if (controller != null) {
                logEvent(context, "mediaSessionFound", "Active session: " + packageName);
                finish(controller);
            }
        }

        private void finish(MediaController controller) {
            if (!finished.compareAndSet(false, true)) return;
            handler.removeCallbacks(timeout);
            if (controller == null) {
                logEvent(context, "mediaRestoreEnded", "No matching MediaSession; PLAY not sent");
            }
            if (listenerRegistered) {
                listenerRegistered = false;
                try {
                    manager.removeOnActiveSessionsChangedListener(listener);
                } catch (RuntimeException ignored) {
                }
            }
            if (controller != null && playing) {
                try {
                    controller.getTransportControls().play();
                    logEvent(context, "mediaPlaySent", "TransportControls.play(): " + packageName);
                } catch (RuntimeException error) {
                    logEvent(context, "mediaPlayFailed", error.toString());
                }
            }
            notifyCompletion(completion);
        }

        @Override
        public void cancel() {
            logEvent(context, "mediaRestoreCancelled", "Restoration cancelled: " + packageName);
            finish(null);
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
                fail();
                return;
            }

            handler.postDelayed(timeout, BLUETOOTH_TIMEOUT_MS);

            if (launchActivity) {
                registerListener();
                if (state.get() != State.ACTIVE) return;
                launchBluetoothActivity(context);
            } else {
                sessionReady = hasVendorSession(getActiveSessions());
                if (sessionReady) logEvent(context, "bluetoothMediaSessionFound", "Existing vendor session");
                if (!sessionReady) registerListener();
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
                listenerRegistered = true;
                logEvent(context, "bluetoothSessionListenerRegistered", "Waiting for vendor MediaSession");
            } catch (RuntimeException error) {
                logEvent(context, "bluetoothSessionListenerFailed", error.toString());
                fail();
            }
        }

        private void onSessionsChanged(List<MediaController> sessions) {
            if (state.get() != State.ACTIVE || !hasVendorSession(sessions)) return;
            logEvent(context, "bluetoothMediaSessionFound", "Vendor MediaSession became active");
            synchronized (lock) {
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
                workerSubmitted = true;
                service = binder;
            }

            try {
                BLUETOOTH_WORKER.execute(() -> restoreBluetooth(service));
            } catch (RuntimeException error) {
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
            logEvent(context, "bluetoothRestoreTimeout", "10-second restoration deadline reached");
            if (state.compareAndSet(State.ACTIVE, State.CANCELLED)) {
                finishResources();
            } else if (state.get() == State.PLAY_COMMITTED) {
                finishResources();
            }
        }

        private void fail() {
            if (state.compareAndSet(State.ACTIVE, State.COMPLETED)) {
                logEvent(context, "bluetoothRestoreFailed", "Readiness or connection check failed");
            }
            finishResources();
        }

        private void finishResources() {
            if (!cleaned.compareAndSet(false, true)) return;
            handler.removeCallbacks(timeout);

            if (listenerRegistered) {
                listenerRegistered = false;
                try {
                    manager.removeOnActiveSessionsChangedListener(listener);
                } catch (RuntimeException ignored) {
                }
            }

            synchronized (lock) {
                if (bound) {
                    bound = false;
                    try {
                        context.unbindService(connection);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                binder = null;
            }
            notifyCompletion(completion);
        }

        @Override
        public void cancel() {
            if (state.compareAndSet(State.ACTIVE, State.CANCELLED)
                    || state.get() == State.PLAY_COMMITTED) {
                logEvent(context, "bluetoothRestoreCancelled", "Restoration cancelled");
                finishResources();
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
