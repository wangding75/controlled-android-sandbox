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

    private static final long DEFAULT_BIND_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10L);
    private static final int MAX_PRE_DISPATCH_DEAD_RETRIES = 1;

    private final Service owner;
    private final DisconnectListener disconnectListener;
    private final long bindTimeoutMillis;
    private final Map<Integer, GuestConnection> connections = new ConcurrentHashMap<>();

    RuntimeGuestConnectionPool(Service owner, DisconnectListener disconnectListener) {
        this(owner, disconnectListener, DEFAULT_BIND_TIMEOUT_MILLIS);
    }

    RuntimeGuestConnectionPool(Service owner, DisconnectListener disconnectListener,
                               long bindTimeoutMillis) {
        if (bindTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("bindTimeoutMillis must be positive");
        }
        this.owner = owner;
        this.disconnectListener = disconnectListener;
        this.bindTimeoutMillis = bindTimeoutMillis;
    }

    Bundle call(int slot, GuestCall call) throws Exception {
        int preDispatchDeadRetries = 0;
        while (true) {
            GuestConnection connection = requireConnection(slot);
            IGuestProcess guest;
            try {
                guest = connection.requireGuest();
            } catch (IllegalStateException error) {
                if (!connection.isAlive()
                        && preDispatchDeadRetries++ < MAX_PRE_DISPATCH_DEAD_RETRIES) {
                    disconnect(connection, "DEAD_BINDER");
                    continue;
                }
                throw error;
            }
            try {
                return call.run(guest);
            } catch (Exception error) {
                if (!connection.isAlive()) {
                    disconnect(connection,
                            "BINDER_CALL_FAILED:" + error.getClass().getSimpleName());
                }
                // The Guest call may already have produced side effects. Do not replay it.
                throw error;
            }
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
        retire(connection, "RELEASED", false);
    }

    @Override
    public void close() {
        for (Integer slot : new ArrayList<>(connections.keySet())) {
            release(slot);
        }
    }

    private GuestConnection requireConnection(int slot) throws Exception {
        int immediateDeadRetries = 0;
        while (true) {
            GuestConnection connection;
            GuestConnection stale = null;
            RuntimeException bindFailure = null;
            synchronized (this) {
                connection = connections.get(slot);
                if (connection != null && connection.isAlive()) {
                    return connection;
                }
                if (connection == null || !connection.isBinding()) {
                    if (connection != null && connections.remove(slot, connection)) {
                        stale = connection;
                    }
                    connection = new GuestConnection(slot);
                    connections.put(slot, connection);
                    try {
                        // Publish and start the in-flight binding under one lock boundary so another
                        // caller can never time out a placeholder that has not reached bindService.
                        startBinding(connection);
                    } catch (RuntimeException error) {
                        bindFailure = error;
                    }
                }
            }

            if (stale != null) {
                retire(stale, stale.failureReasonOr("DEAD_BINDER"), true);
            }
            if (bindFailure != null) {
                throw bindFailure;
            }

            if (!connection.await(bindTimeoutMillis, TimeUnit.MILLISECONDS)) {
                connection.markFailure("BIND_TIMEOUT");
                disconnect(connection, "BIND_TIMEOUT");
                throw new IllegalStateException("BIND_TIMEOUT");
            }
            if (connection.isAlive()) {
                return connection;
            }

            String reason = connection.failureReasonOr("DEAD_BINDER");
            disconnect(connection, reason);
            if ("DEAD_BINDER".equals(reason)
                    && immediateDeadRetries++ < MAX_PRE_DISPATCH_DEAD_RETRIES) {
                continue;
            }
            throw new IllegalStateException(reason);
        }
    }

    private void startBinding(GuestConnection connection) {
        Intent intent = new Intent(owner, RuntimeStubComponents.serviceClassFor(connection.slot));
        final boolean accepted;
        try {
            accepted = owner.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException error) {
            connection.markFailure("BIND_REJECTED");
            disconnect(connection, "BIND_REJECTED");
            throw new IllegalStateException("BIND_REJECTED", error);
        }
        if (!accepted) {
            connection.markFailure("BIND_REJECTED");
            disconnect(connection, "BIND_REJECTED");
            throw new IllegalStateException("BIND_REJECTED");
        }
    }

    private void disconnect(GuestConnection source, String reason) {
        synchronized (this) {
            if (connections.get(source.slot) != source) {
                return;
            }
            connections.remove(source.slot);
        }
        retire(source, reason, !source.closing);
    }

    private void retire(GuestConnection source, String reason, boolean notify) {
        source.markFailure(reason);
        source.unlinkDeath();
        unbind(source);
        if (notify && source.claimDisconnectNotification()) {
            disconnectListener.onDisconnect(source.slot, reason);
        }
    }

    private void unbind(GuestConnection connection) {
        if (!connection.claimUnbind()) {
            return;
        }
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
        private volatile String failureReason;
        private boolean unbindClaimed;
        private boolean disconnectNotificationClaimed;

        private GuestConnection(int slot) {
            this.slot = slot;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            boolean current;
            synchronized (RuntimeGuestConnectionPool.this) {
                current = connections.get(slot) == this && !closing;
                if (current) {
                    binderToken = service;
                    guest = IGuestProcess.Stub.asInterface(service);
                }
            }
            if (!current) {
                markFailure("DISCONNECTED");
                connected.countDown();
                return;
            }
            try {
                service.linkToDeath(this, 0);
                if (!service.isBinderAlive()) {
                    markFailure("DEAD_BINDER");
                    guest = null;
                    binderToken = null;
                }
            } catch (Throwable error) {
                markFailure("DEAD_BINDER");
                guest = null;
                binderToken = null;
            } finally {
                connected.countDown();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            clearAndDisconnect("DISCONNECTED");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            clearAndDisconnect("DISCONNECTED");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            clearAndDisconnect("DISCONNECTED");
        }

        @Override
        public void binderDied() {
            clearAndDisconnect("BINDER_DIED");
        }

        private void clearAndDisconnect(String reason) {
            markFailure(reason);
            guest = null;
            binderToken = null;
            connected.countDown();
            disconnect(this, reason);
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return connected.await(timeout, unit);
        }

        private boolean isBinding() {
            return connected.getCount() != 0L && failureReason == null && !closing;
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

        private synchronized void markFailure(String reason) {
            if (failureReason == null) {
                failureReason = reason;
            }
        }

        private String failureReasonOr(String fallback) {
            String reason = failureReason;
            return reason == null ? fallback : reason;
        }

        private synchronized boolean claimUnbind() {
            if (unbindClaimed) {
                return false;
            }
            unbindClaimed = true;
            return true;
        }

        private synchronized boolean claimDisconnectNotification() {
            if (disconnectNotificationClaimed) {
                return false;
            }
            disconnectNotificationClaimed = true;
            return true;
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
