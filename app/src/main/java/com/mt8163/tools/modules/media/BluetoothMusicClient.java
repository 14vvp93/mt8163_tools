package com.mt8163.tools.modules.media;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BluetoothMusicClient {
    public interface Listener {
        void onSnapshot(String state);
    }

    private static final String SERVICE_PACKAGE = "com.hcn.bluetoothservice";
    private static final String SERVICE_CLASS =
            "com.hcn.bluetooth.service.BluetoothMusicService";
    private static final String SERVICE_ACTION =
            "com.hcn.bluetooth.service.BluetoothMusicService";
    private static final String SERVICE_DESCRIPTOR =
            "com.hcn.bluetooth.service.IBluetoothMusicService";
    private static final int TRANSACTION_IS_A2DP_PLAYING = 3;
    private static final long BIND_TIMEOUT_MS = 5000L;

    private static final ExecutorService BINDER_WORKER =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MediaBluetoothSnapshot");
                thread.setDaemon(true);
                return thread;
            });
    private static final AtomicBoolean BINDER_WORKER_BUSY = new AtomicBoolean();

    private final Context context;
    private final Listener listener;
    private final Object lock = new Object();
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutRunnable =
            () -> finish(MediaSettingsStore.PLAYBACK_UNKNOWN);

    private boolean bound;
    private boolean closed;
    private boolean completed;
    private boolean queryStarted;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder == null) {
                finish(MediaSettingsStore.PLAYBACK_UNKNOWN);
                return;
            }

            if (!BINDER_WORKER_BUSY.compareAndSet(false, true)) {
                finish(MediaSettingsStore.PLAYBACK_UNKNOWN);
                return;
            }

            try {
                BINDER_WORKER.execute(() -> {
                    try {
                        querySnapshot(binder);
                    } finally {
                        BINDER_WORKER_BUSY.set(false);
                    }
                });
            } catch (RuntimeException error) {
                BINDER_WORKER_BUSY.set(false);
                finish(MediaSettingsStore.PLAYBACK_UNKNOWN);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            finish(MediaSettingsStore.PLAYBACK_UNKNOWN);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            finish(MediaSettingsStore.PLAYBACK_UNKNOWN);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            finish(MediaSettingsStore.PLAYBACK_UNKNOWN);
        }
    };

    public BluetoothMusicClient(Context context, Listener listener) {
        if (context == null) throw new IllegalArgumentException("context == null");
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean connect() {
        synchronized (lock) {
            if (closed || bound) return false;

            Intent intent = new Intent(SERVICE_ACTION);
            intent.setComponent(new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS));
            try {
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException error) {
                bound = false;
            }

            if (!bound) return false;
            if (!timeoutHandler.postDelayed(timeoutRunnable, BIND_TIMEOUT_MS)) {
                unbindLocked();
                return false;
            }
            return true;
        }
    }

    public void disconnect() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            timeoutHandler.removeCallbacks(timeoutRunnable);
            unbindLocked();
        }
    }

    private void querySnapshot(IBinder binder) {
        synchronized (lock) {
            if (closed || completed || queryStarted) return;
            queryStarted = true;
        }

        String state;
        try {
            state = queryBoolean(binder, TRANSACTION_IS_A2DP_PLAYING)
                    ? MediaSettingsStore.PLAYBACK_PLAYING
                    : MediaSettingsStore.PLAYBACK_PAUSED;
        } catch (RemoteException | RuntimeException error) {
            state = MediaSettingsStore.PLAYBACK_UNKNOWN;
        }
        finish(state);
    }

    private boolean queryBoolean(IBinder binder, int transaction) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            if (!binder.transact(transaction, data, reply, 0)) {
                throw new RemoteException("Unsupported Bluetooth transaction " + transaction);
            }
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void finish(String state) {
        synchronized (lock) {
            if (closed || completed) return;
            completed = true;
            timeoutHandler.removeCallbacks(timeoutRunnable);
            unbindLocked();
        }

        if (listener != null) {
            try {
                listener.onSnapshot(state);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void unbindLocked() {
        if (!bound) return;
        bound = false;
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
