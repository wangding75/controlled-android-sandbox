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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private static final ExecutorService BOUNDED_CALL_WORKERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "sandbox-bounded-guest-call");
        thread.setDaemon(true);
        return thread;
    });

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

    /**
     * Executes a teardown Binder call without allowing an unresponsive Guest to pin the Broker
     * forever.  Normal Guest operations remain synchronous and retain their existing semantics;
     * this bound is intentionally used only for lifecycle teardown.
     */
    Bundle callWithTimeout(int slot, GuestCall call, long timeoutMillis) throws Exception {
        Future<Bundle> future = BOUNDED_CALL_WORKERS.submit(() -> call(slot, call));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            abort(slot, "GUEST_SHUTDOWN_TIMEOUT");
            throw new IllegalStateException("GUEST_SHUTDOWN_TIMEOUT", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("GUEST_SHUTDOWN_INTERRUPTED", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException(cause);
        }
    }

    /**
     * Runs an intentional Guest shutdown and does not complete until the concrete Guest process
     * has gone away.  A successful shutdown Binder call only means that the Guest requested
     * {@code stopSelf()}; ActivityThread may still be dispatching Activity/Service destruction
     * callbacks which can legitimately re-enter the Broker and read the staged APK.  Destructive
     * package transactions must therefore use this physical process-death barrier before their
     * data or APK workspace is removed.
     */
    Bundle callWithTimeoutAndAwaitDisconnect(int slot, GuestCall call, long timeoutMillis)
            throws Exception {
        if (timeoutMillis <= 0L) throw new IllegalArgumentException("timeoutMillis must be positive");
        GuestConnection connection = requireConnection(slot);
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Future<Bundle> future = BOUNDED_CALL_WORKERS.submit(() -> {
            try {
                return call.run(connection.requireGuest());
            } catch (Exception error) {
                if (!connection.isAlive()) {
                    disconnect(connection,
                            "BINDER_CALL_FAILED:" + error.getClass().getSimpleName());
                }
                throw error;
            }
        });
        Bundle result;
        try {
            result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            abort(slot, "GUEST_SHUTDOWN_TIMEOUT");
            throw new IllegalStateException("GUEST_SHUTDOWN_TIMEOUT", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            abort(slot, "GUEST_SHUTDOWN_INTERRUPTED");
            throw new IllegalStateException("GUEST_SHUTDOWN_INTERRUPTED", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException(cause);
        }

        // Keep the death recipient installed while unbinding/stopping the concrete Service.  The
        // normal release() path unlinks first and is intentionally unsuitable for a destructive
        // barrier because it can report success while the old process is still alive.
        if (detachForShutdown(connection)) {
            long remainingNanos = deadline - System.nanoTime();
            boolean terminated = remainingNanos > 0L
                    && connection.awaitTerminated(remainingNanos, TimeUnit.NANOSECONDS);
            connection.unlinkDeath();
            if (!terminated) {
                throw new IllegalStateException("GUEST_SHUTDOWN_PROCESS_TIMEOUT");
            }
        }
        return result;
    }

    /** Detaches a connection that can no longer participate in a bounded lifecycle operation. */
    void abort(int slot, String reason) {
        GuestConnection connection;
        synchronized (this) {
            connection = connections.remove(slot);
        }
        if (connection == null) return;
        connection.closing = true;
        retire(connection, reason == null ? "ABORTED" : reason, false);
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

    private boolean detachForShutdown(GuestConnection connection) {
        synchronized (this) {
            if (!connections.remove(connection.slot, connection)) return false;
            connection.closing = true;
        }
        connection.markFailure("RELEASED");
        // Do not unlink the death recipient here.  The process-death latch is completed by the
        // Binder/service disconnect callback after the framework has finished teardown.
        unbind(connection);
        stopGuestServiceIfUnowned(connection);
        return true;
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
        // A bound-only Guest service otherwise survives after the Binder lease is released.  That
        // leaves the old process-local GuestRuntimeEnvironment resident in the slot and lets a
        // later session collide with the slot's generation.  The logical slot owns exactly one
        // Guest process, so retire the concrete service after releasing this binding.
        stopGuestServiceIfUnowned(source);
        if (notify && source.claimDisconnectNotification()) {
            disconnectListener.onDisconnect(source.slot, reason);
        }
    }

    /**
     * Stops the concrete stub service only while this logical slot is still unowned.
     *
     * <p>A dead connection is retired outside the pool monitor.  A replacement connection can
     * therefore already be published by the time the old connection reaches teardown.  Calling
     * {@code stopService()} unconditionally at that point tears down the replacement process and
     * turns an otherwise recoverable Binder death into a reconnect loop.  Keep the ownership check
     * and the stop operation in the same monitor boundary as replacement publication so a new
     * lease cannot appear between the check and the destructive framework call.</p>
     */
    private void stopGuestServiceIfUnowned(GuestConnection connection) {
        synchronized (this) {
            if (connections.containsKey(connection.slot)) return;
            stopGuestService(connection);
        }
    }

    private void stopGuestService(GuestConnection connection) {
        Class<?> serviceClass = RuntimeStubComponents.serviceClassFor(connection.slot);
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(owner.getPackageName(), serviceClass.getName()));
        owner.stopService(intent);
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
        private final CountDownLatch terminated = new CountDownLatch(1);
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
            IGuestProcess candidate = IGuestProcess.Stub.asInterface(service);
            boolean linked = false;
            boolean published = false;
            try {
                // A connection is not usable until death registration has succeeded. Publishing the
                // Binder before this point lets another caller dispatch into an unowned capability.
                service.linkToDeath(this, 0);
                linked = true;
                synchronized (RuntimeGuestConnectionPool.this) {
                    if (connections.get(slot) == this
                            && !closing
                            && failureReason == null
                            && service.isBinderAlive()) {
                        binderToken = service;
                        guest = candidate;
                        published = true;
                    } else if (failureReason == null) {
                        markFailure(service.isBinderAlive() ? "DISCONNECTED" : "DEAD_BINDER");
                    }
                }
            } catch (Throwable error) {
                markFailure("DEAD_BINDER");
                disconnect(this, failureReasonOr("DEAD_BINDER"));
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            } finally {
                if (!published && linked) {
                    try {
                        service.unlinkToDeath(this, 0);
                    } catch (Throwable ignored) {
                        com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
                        // The candidate may have died while registration was being rolled back.
                    }
                }
                connected.countDown();
            }
            if (!published) {
                disconnect(this, failureReasonOr("DISCONNECTED"));
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
            connected.countDown();
            terminated.countDown();
            disconnect(this, reason);
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return connected.await(timeout, unit);
        }

        private boolean awaitTerminated(long timeout, TimeUnit unit) throws InterruptedException {
            return terminated.await(timeout, unit);
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
            IBinder token;
            synchronized (this) {
                token = binderToken;
                binderToken = null;
                guest = null;
            }
            if (token != null) {
                try {
                    token.unlinkToDeath(this, 0);
                } catch (Throwable ignored) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
                    // Binder may already be dead.
                }
            }
        }
    }
}
