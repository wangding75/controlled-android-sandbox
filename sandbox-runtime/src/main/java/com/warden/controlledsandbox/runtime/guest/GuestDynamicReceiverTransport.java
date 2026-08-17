package com.warden.controlledsandbox.runtime.guest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;

import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Owns dynamic Receiver leases for one Guest generation.
 *
 * <p>Dynamic registration is a different lifetime from manifest/ordered delivery: Android
 * keeps a Host ReceiverDispatcher registered until explicit unregister or process teardown,
 * while the Guest callback may be asynchronous through {@code goAsync()}.  Keeping that lease
 * and PendingResult bridge separate makes close/recovery a single fail-closed boundary.</p>
 */
final class GuestDynamicReceiverTransport implements AutoCloseable {
    private final GuestRuntimeEnvironment.Session session;
    private final Object leasesLock = new Object();
    private final Map<String, DynamicReceiverLease> leases = new HashMap<>();
    private volatile boolean closed;

    GuestDynamicReceiverTransport(GuestRuntimeEnvironment.Session session) {
        if (session == null) throw new IllegalArgumentException("session is required");
        this.session = session;
    }

    Intent register(String receiverId, BroadcastReceiver guestReceiver, IntentFilter filter,
                    String permission, Handler scheduler, int flags) {
        requireOpen();
        if (receiverId == null || receiverId.trim().isEmpty()) {
            throw new IllegalArgumentException("FRAMEWORK_DYNAMIC_RECEIVER_ID_MISSING");
        }
        if (guestReceiver == null || filter == null) {
            throw new IllegalArgumentException("FRAMEWORK_DYNAMIC_RECEIVER_ARGUMENT_MISSING");
        }
        DynamicReceiverLease lease = new DynamicReceiverLease(receiverId, guestReceiver, filter,
                permission, scheduler, flags);
        try {
            Intent sticky = session.mainThread.call(() -> registerHost(lease));
            synchronized (leasesLock) {
                requireOpen();
                if (leases.put(receiverId, lease) != null) {
                    unregisterHost(lease);
                    throw new IllegalStateException("FRAMEWORK_DYNAMIC_RECEIVER_DUPLICATE");
                }
            }
            Bundle event = identityResult();
            event.putString(RuntimeKeys.STATUS, "FRAMEWORK_DYNAMIC_RECEIVER_REGISTERED");
            event.putString(RuntimeKeys.RECEIVER_ID, receiverId);
            RuntimeEventLog.event("GUEST_RECEIVER_FRAMEWORK_REGISTERED", event);
            return sanitizeSticky(sticky);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            try { unregisterHost(lease); }
            catch (Throwable cleanup) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cleanup);
            }
            throw error instanceof RuntimeException
                    ? (RuntimeException) error : new IllegalStateException(error);
        }
    }

    void unregister(String receiverId) {
        DynamicReceiverLease lease;
        synchronized (leasesLock) { lease = leases.remove(receiverId); }
        if (lease == null) return;
        try {
            session.mainThread.call(() -> {
                unregisterHost(lease);
                return null;
            });
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_RECEIVER_FRAMEWORK",
                    "dynamic unregister failed id=" + receiverId, error);
        }
    }

    private void onReceive(DynamicReceiverLease lease, Intent hostIntent) {
        // unregisterReceiver() and Handler delivery can race.  A closed generation must not
        // invoke Guest code or retain a PendingResult after clear/delete/recovery begins.
        if (closed) return;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Object hostPending = null;
        try {
            Thread.currentThread().setContextClassLoader(session.context.getClassLoader());
            if (closed) return;
            hostPending = pendingResult(lease.hostReceiver);
            setPendingResult(lease.guestReceiver, hostPending);
            Intent guestIntent = hostIntent == null ? null : new Intent(hostIntent);
            if (guestIntent != null && guestIntent.getExtras() != null) {
                guestIntent.getExtras().setClassLoader(session.context.getClassLoader());
            }
            lease.guestReceiver.onReceive(session.context, guestIntent);
            Object guestPending = pendingResult(lease.guestReceiver);
            // If teardown won while Guest code was running, leave the original Host result in
            // place so Android can complete its ReceiverDispatcher without calling old Guest
            // code again.
            setPendingResult(lease.hostReceiver, closed
                    ? hostPending : (guestPending == null ? null : guestPending));
            Bundle event = identityResult();
            event.putString(RuntimeKeys.STATUS, "BROADCAST_DELIVERED");
            event.putString(RuntimeKeys.RECEIVER_ID, lease.receiverId);
            event.putString(ComponentOperations.ACTION,
                    guestIntent == null || guestIntent.getAction() == null
                            ? "" : guestIntent.getAction());
            RuntimeEventLog.event("GUEST_RECEIVER_FRAMEWORK_DYNAMIC_DELIVERED", event);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            try { setPendingResult(lease.hostReceiver, hostPending); }
            catch (Throwable ignored) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(
                        ignored);
            }
            Bundle event = identityResult();
            event.putString(RuntimeKeys.STATUS, "FAILED");
            event.putString(RuntimeKeys.RECEIVER_ID, lease.receiverId);
            event.putString(RuntimeKeys.ERROR_TYPE, error.getClass().getSimpleName());
            RuntimeEventLog.event("GUEST_RECEIVER_FRAMEWORK_DYNAMIC_FAILED", event);
            if (!closed) {
                throw error instanceof RuntimeException
                        ? (RuntimeException) error : new IllegalStateException(error);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private Intent registerHost(DynamicReceiverLease lease) throws Exception {
        Context host = session.context.hostServiceContext();
        String permission = lease.permission == null || lease.permission.trim().isEmpty()
                ? null : lease.permission;
        try {
            Method withFlags = Context.class.getMethod("registerReceiver",
                    BroadcastReceiver.class, IntentFilter.class, String.class, Handler.class,
                    int.class);
            Object result = withFlags.invoke(host, lease.hostReceiver, lease.filter, permission,
                    lease.scheduler, lease.flags);
            return result instanceof Intent ? (Intent) result : null;
        } catch (NoSuchMethodException api32) {
            Object result = host.registerReceiver(lease.hostReceiver, lease.filter, permission,
                    lease.scheduler);
            return result instanceof Intent ? (Intent) result : null;
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw error;
        }
    }

    private void unregisterHost(DynamicReceiverLease lease) {
        try {
            session.context.hostServiceContext().unregisterReceiver(lease.hostReceiver);
        } catch (IllegalArgumentException alreadyUnregistered) {
            // Process teardown can race ActivityThread receiver dispatcher cleanup.
        }
    }

    private Bundle identityResult() {
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.SESSION_ID, session.spec.sessionId);
        result.putLong(RuntimeKeys.GENERATION, session.spec.generation);
        result.putInt(RuntimeKeys.PROCESS_SLOT, session.spec.processSlot);
        result.putString(RuntimeKeys.PACKAGE_NAME, session.spec.packageName);
        result.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.spec.virtualUserId);
        result.putString(RuntimeKeys.PROCESS_NAME, session.spec.processName);
        return result;
    }

    private static Intent sanitizeSticky(Intent sticky) {
        if (sticky == null) return null;
        try {
            Bundle wire = new Bundle();
            RuntimeIntentWireCodec.encode(wire, sticky);
            return RuntimeIntentWireCodec.decode(wire);
        } catch (RuntimeException error) {
            android.util.Log.w("CS_RECEIVER_FRAMEWORK",
                    "sticky broadcast projection dropped unsupported host payload", error);
            return null;
        }
    }

    private static Object pendingResult(BroadcastReceiver receiver) throws Exception {
        return BroadcastReceiver.class.getMethod("getPendingResult").invoke(receiver);
    }

    private static void setPendingResult(BroadcastReceiver receiver, Object result)
            throws Exception {
        Class<?> type = Class.forName("android.content.BroadcastReceiver$PendingResult");
        BroadcastReceiver.class.getMethod("setPendingResult", type).invoke(receiver,
                new Object[]{result});
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("GUEST_RECEIVER_FRAMEWORK_BRIDGE_CLOSED");
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ArrayList<DynamicReceiverLease> toClose;
        synchronized (leasesLock) {
            toClose = new ArrayList<>(leases.values());
            leases.clear();
        }
        for (DynamicReceiverLease lease : toClose) {
            try {
                session.mainThread.call(() -> {
                    unregisterHost(lease);
                    return null;
                });
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
        }
    }

    private final class DynamicReceiverLease {
        final String receiverId;
        final BroadcastReceiver guestReceiver;
        final IntentFilter filter;
        final String permission;
        final Handler scheduler;
        final int flags;
        final FrameworkDynamicReceiver hostReceiver;

        DynamicReceiverLease(String receiverId, BroadcastReceiver guestReceiver,
                             IntentFilter filter, String permission, Handler scheduler,
                             int flags) {
            this.receiverId = receiverId;
            this.guestReceiver = guestReceiver;
            this.filter = filter;
            this.permission = permission == null ? "" : permission;
            this.scheduler = scheduler;
            this.flags = flags;
            this.hostReceiver = new FrameworkDynamicReceiver();
        }

        final class FrameworkDynamicReceiver extends BroadcastReceiver {
            @Override public void onReceive(Context hostContext, Intent hostIntent) {
                GuestDynamicReceiverTransport.this.onReceive(DynamicReceiverLease.this,
                        hostIntent);
            }
        }
    }
}
