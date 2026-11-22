package com.mt8163.tools.modules.media;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

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

    private final Context context;
    private final Listener listener;
    private final Object lock = new Object();

    private boolean bound;
    private boolean closed;
    private boolean completed;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (lock) {
                if (closed || completed) return;
            }

            String state;
            try {
                boolean a2dpConnected = queryBoolean(binder, 1);
                boolean avrcpConnected = queryBoolean(binder, 2);
                boolean playing = queryBoolean(binder, 3);
                state = a2dpConnected && avrcpConnected
                        ? (playing ? "start" : "stop") : "unavailable";
            } catch (RemoteException | RuntimeException error) {
                state = "unavailable";
            }

            finish(state);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            finish("unavailable");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            finish("unavailable");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            finish("unavailable");
        }
    };

    public BluetoothMusicClient(Context context, Listener listener) {
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
            return bound;
        }
    }

    public void disconnect() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            unbindLocked();
        }
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
            unbindLocked();
        }
        listener.onSnapshot(state);
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
