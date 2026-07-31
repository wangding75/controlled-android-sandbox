package com.warden.controlledsandbox.runtime.broker;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IGuestProcess;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns binding, binder-death handling, and cleanup for ordinary Guest process slots. */
final class RuntimeGuestConnectionPool implements AutoCloseable {
    interface GuestCall {
        Bundle run(IGuestProcess guest) throws Exception;
    }

    interface DisconnectListener {
        void onDisconnect(int slot, String reason);
    }

    private static final long BIND_TIMEOUT_SECONDS = 10L;

    private final Service owner;
    private final DisconnectListener disconnectListener;
    private final Map<Integer, GuestConnection> connections = new ConcurrentHashMap<>();

    RuntimeGuestConnectionPool(Service owner, DisconnectListener disconnectListener) {
        this.owner = owner;
        this.disconnectListener = disconnectListener;
    }

    Bundle call(int slot, GuestCall call) throws Exception {
        GuestConnection connection = requireConnection(slot);
        try {
            return call.run(connection.requireGuest());
        } catch (Exception error) {
            if (!connection.isAlive()) {
                disconnect(connection, "BINDER_CALL_FAILED:" + error.getClass().getSimpleName());
            }
            throw error;
        }
    }

    void release(int slot) {
        GuestConnection connection;
        synchronized (this) {
            connection = connections.remove(slot);
        }
        if (connection == null) {
            return;
        }
        connection.closing = true;
        connection.unlinkDeath();
        unbind(connection);
    }

    @Override
    public void close() {
        for (Integer slot : new ArrayList<>(connections.keySet())) {
            release(slot);
        }
    }

    private GuestConnection requireConnection(int slot) throws Exception {
        GuestConnection connection;
        synchronized (this) {
            connection = connections.get(slot);
            if (connection != null && connection.isAlive()) {
                return connection;
            }
            if (connection == null) {
                connection = new GuestConnection(slot);
                connections.put(slot, connection);
                Intent intent = new Intent(owner, RuntimeStubComponents.serviceClassFor(slot));
                if (!owner.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    connections.remove(slot);
                    throw new IllegalStateException("BIND_FAILED");
                }
            }
        }
        if (!connection.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS) || !connection.isAlive()) {
            disconnect(connection, "BIND_TIMEOUT");
            throw new IllegalStateException("BIND_TIMEOUT");
        }
        return connection;
    }

    private void disconnect(GuestConnection source, String reason) {
        synchronized (this) {
            if (connections.get(source.slot) != source) {
                return;
            }
            connections.remove(source.slot);
        }
        source.unlinkDeath();
        unbind(source);
        if (!source.closing) {
            disconnectListener.onDisconnect(source.slot, reason);
        }
    }

    private void unbind(GuestConnection connection) {
        try {
            owner.unbindService(connection);
        } catch (Exception ignored) {
            // The framework can report an already-unbound connection after binder death.
        }
    }

    private final class GuestConnection implements ServiceConnection, IBinder.DeathRecipient {
        private final int slot;
        private final CountDownLatch connected = new CountDownLatch(1);
        private volatile IGuestProcess guest;
        private volatile IBinder binderToken;
        private volatile boolean closing;

        private GuestConnection(int slot) {
            this.slot = slot;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binderToken = service;
            guest = IGuestProcess.Stub.asInterface(service);
            try {
                service.linkToDeath(this, 0);
            } catch (Throwable error) {
                guest = null;
                binderToken = null;
            } finally {
                connected.countDown();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            clearAndDisconnect("SERVICE_DISCONNECTED");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            clearAndDisconnect("BINDING_DIED");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            clearAndDisconnect("NULL_BINDING");
        }

        @Override
        public void binderDied() {
            guest = null;
            binderToken = null;
            disconnect(this, "BINDER_DIED");
        }

        private void clearAndDisconnect(String reason) {
            guest = null;
            binderToken = null;
            connected.countDown();
            disconnect(this, reason);
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return connected.await(timeout, unit);
        }

        private boolean isAlive() {
            IBinder token = binderToken;
            return guest != null && token != null && token.isBinderAlive();
        }

        private IGuestProcess requireGuest() {
            IGuestProcess value = guest;
            if (value == null || !isAlive()) {
                throw new IllegalStateException("GUEST_BINDER_DEAD");
            }
            return value;
        }

        private void unlinkDeath() {
            IBinder token = binderToken;
            if (token != null) {
                try {
                    token.unlinkToDeath(this, 0);
                } catch (Throwable ignored) {
                    // Binder may already be dead.
                }
            }
        }
    }
}
