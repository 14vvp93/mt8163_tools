package com.mt8163.tools.xposed.hooks.media;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

public final class BluetoothMusicClient {
    public interface Listener {
        void onSnapshot(String state);
        void onPlaybackState(String state);
        void onConnectionLost();
    }

    private static final String SERVICE_PACKAGE = "com.hcn.bluetoothservice";
    private static final String SERVICE_CLASS =
            "com.hcn.bluetooth.service.BluetoothMusicService";
    private static final String SERVICE_ACTION =
            "com.hcn.bluetooth.service.BluetoothMusicService";
    private static final String SERVICE_DESCRIPTOR =
            "com.hcn.bluetooth.service.IBluetoothMusicService";
    private static final String CALLBACK_DESCRIPTOR =
            "com.hcn.bluetooth.service.IMusicCallback";

    private final Context context;
    private final Listener listener;
    private final Object lock = new Object();
    private final IBinder.DeathRecipient deathRecipient = this::handleDisconnect;
    private final Callback callback = new Callback();

    private IBinder service;
    private boolean bound;
    private boolean registered;
    private boolean closed;
    private boolean initialSnapshotSent;
    private String lastPlaybackState;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (lock) {
                if (closed) return;
                service = binder;
            }

            try {
                binder.linkToDeath(deathRecipient, 0);
                registerCallback(binder);
                publishInitialSnapshot(binder);
            } catch (RemoteException | RuntimeException error) {
                handleDisconnect();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            handleDisconnect();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            handleDisconnect();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            handleDisconnect();
        }
    };

    public BluetoothMusicClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean connect() {
        synchronized (lock) {
            if (closed || bound) return bound;
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
            clearConnectionLocked();
        }
    }

    private void registerCallback(IBinder binder) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStrongBinder(callback);
            binder.transact(10, data, reply, 0);
            reply.readException();
            synchronized (lock) {
                if (service == binder && !closed) registered = true;
            }
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void publishInitialSnapshot(IBinder binder) throws RemoteException {
        synchronized (lock) {
            if (closed || service != binder || initialSnapshotSent) return;
            boolean connected = queryBoolean(binder, 1);
            boolean playing = queryBoolean(binder, 3);
            String state = connected ? (playing ? "start" : "stop") : "unavailable";
            if (!initialSnapshotSent) {
                initialSnapshotSent = true;
                lastPlaybackState = state;
                listener.onSnapshot(state);
            }
        }
    }

    private boolean queryBoolean(IBinder binder, int transaction) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            binder.transact(transaction, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void dispatchPlaybackState(String state) {
        synchronized (lock) {
            if (closed) return;
            lastPlaybackState = state;
            if (!initialSnapshotSent) {
                initialSnapshotSent = true;
                listener.onSnapshot(state);
            } else {
                listener.onPlaybackState(state);
            }
        }
    }

    public void requestSnapshot() {
        IBinder current;
        String state;
        synchronized (lock) {
            if (closed) return;
            current = service;
            state = initialSnapshotSent ? lastPlaybackState : null;
        }

        if (state != null) {
            listener.onSnapshot(state);
        } else if (current != null) {
            try {
                publishInitialSnapshot(current);
            } catch (RemoteException | RuntimeException error) {
                handleDisconnect();
            }
        }
    }

    private void handleDisconnect() {
        synchronized (lock) {
            if (closed) return;
            clearConnectionLocked();
            listener.onConnectionLost();
        }
    }

    private void clearConnectionLocked() {
        IBinder current = service;
        service = null;

        if (current != null) {
            if (registered && current.isBinderAlive()) {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(SERVICE_DESCRIPTOR);
                    data.writeStrongBinder(callback);
                    current.transact(11, data, reply, 0);
                    reply.readException();
                } catch (RemoteException | RuntimeException ignored) {
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
            try {
                current.unlinkToDeath(deathRecipient, 0);
            } catch (RuntimeException ignored) {
            }
        }

        registered = false;
        if (bound) {
            bound = false;
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private final class Callback extends Binder {
        Callback() {
            attachInterface(null, CALLBACK_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }

            data.enforceInterface(CALLBACK_DESCRIPTOR);
            switch (code) {
                case 1:
                    int a2dpState = data.readInt();
                    if (a2dpState != 2) dispatchPlaybackState("stop");
                    if (reply != null) reply.writeNoException();
                    return true;
                case 2:
                    data.readInt();
                    if (reply != null) reply.writeNoException();
                    return true;
                case 3:
                    data.readString();
                    data.readString();
                    data.readString();
                    if (reply != null) reply.writeNoException();
                    return true;
                case 4:
                    String state = "unavailable";
                    if (data.readInt() != 0) {
                        int playbackState = data.readInt();
                        data.readLong();
                        data.readLong();
                        data.readFloat();
                        data.readLong();
                        data.readLong();
                        state = normalizeState(playbackState);
                    }
                    dispatchPlaybackState(state);
                    if (reply != null) reply.writeNoException();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    private static String normalizeState(int state) {
        switch (state) {
            case 3:
                return "start";
            case 2:
                return "paused";
            case 1:
                return "stop";
            case 0:
                return "unavailable";
            case 6:
            case 8:
                return "buffering";
            case 4:
                return "fast_forwarding";
            case 5:
                return "rewinding";
            default:
                return "unavailable";
        }
    }
}
