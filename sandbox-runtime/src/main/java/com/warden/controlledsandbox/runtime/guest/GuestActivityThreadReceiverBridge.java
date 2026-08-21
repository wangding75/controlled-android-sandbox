package com.warden.controlledsandbox.runtime.guest;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import com.warden.controlledsandbox.runtime.component.receiver.GuestReceiverStubNames;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ActivityThread receiver transport owned by one Guest process.
 *
 * <p>Receiver delivery is deliberately separate from the Service bridge.  Both are dispatched
 * by ActivityThread.H, but their leases, completion tokens, timeout rules and cleanup semantics
 * are independent.  Keeping this boundary explicit prevents a receiver timeout or dynamic
 * registration teardown from mutating the Service token tables.</p>
 */
final class GuestActivityThreadReceiverBridge implements AutoCloseable {
    private static final int RECEIVER = 113;

    private final GuestRuntimeEnvironment.Session session;
    private final Object receiverWaiterLock = new Object();
    private final Object orderedDispatchLock = new Object();
    private final Map<String, ReceiverWaiter> receiverWaiters = new HashMap<>();
    private final GuestDynamicReceiverTransport dynamicTransport;
    private volatile boolean closed;

    GuestActivityThreadReceiverBridge(GuestRuntimeEnvironment.Session session) {
        if (session == null) throw new IllegalArgumentException("session is required");
        this.session = session;
        this.dynamicTransport = new GuestDynamicReceiverTransport(session);
    }

    boolean handles(Message message) {
        if (closed || message == null || message.what != RECEIVER) return false;
        return isFrameworkReceiverRoute(receiverIntent(message.obj));
    }

    void handle(Object data) throws Exception {
        if (closed) throw new IllegalStateException("GUEST_RECEIVER_FRAMEWORK_BRIDGE_CLOSED");
        handleFrameworkReceiver(data);
    }

    Bundle dispatchFrameworkReceiver(Bundle request, String guestClass) {
        boolean ordered = request != null
                && request.getBoolean(RuntimeKeys.BROADCAST_ORDERED, false);
        if (ordered) {
            synchronized (orderedDispatchLock) {
                return dispatchFrameworkReceiverInternal(request, guestClass, true);
            }
        }
        return dispatchFrameworkReceiverInternal(request, guestClass, false);
    }

    private Bundle dispatchFrameworkReceiverInternal(Bundle request, String guestClass,
                                                      boolean ordered) {
        if (closed) throw new IllegalStateException("GUEST_RECEIVER_FRAMEWORK_BRIDGE_CLOSED");
        if (guestClass == null || guestClass.trim().isEmpty()) {
            throw new IllegalArgumentException("FRAMEWORK_RECEIVER_COMPONENT_MISSING");
        }
        String routeId = UUID.randomUUID().toString();
        Bundle routed = new Bundle(request == null ? new Bundle() : request);
        routed.putString(RuntimeKeys.COMPONENT_CLASS, guestClass);
        routed.putString(RuntimeKeys.FRAMEWORK_RECEIVER_ROUTE_ID, routeId);
        ReceiverWaiter waiter = ordered ? new ReceiverWaiter(routeId, guestClass,
                routed.getString(RuntimeKeys.ORDERED_RECEIVER_TOKEN, ""),
                routed.getBinder(RuntimeKeys.ORDERED_RECEIVER_COMPLETION_BINDER)) : null;
        if (waiter != null) {
            synchronized (receiverWaiterLock) { receiverWaiters.put(routeId, waiter); }
        }
        try {
            session.mainThread.call(() -> {
                Intent guestIntent = RuntimeIntentWireCodec.decode(routed);
                Intent hostIntent = new Intent(guestIntent);
                // Internal capability/session state must not cross the host ActivityThread
                // parcel boundary. Re-encode only the guest Intent wire contract.
                Bundle intentEnvelope = new Bundle();
                RuntimeIntentWireCodec.encode(intentEnvelope, guestIntent);
                hostIntent.setComponent(new ComponentName(
                        session.context.hostServiceContext(),
                        GuestReceiverStubNames.classNameFor(session.spec.processSlot)));
                hostIntent.putExtra(RuntimeKeys.FRAMEWORK_RECEIVER_ROUTE, true);
                hostIntent.putExtra(RuntimeKeys.SESSION_ID, session.spec.sessionId);
                hostIntent.putExtra(RuntimeKeys.GENERATION, session.spec.generation);
                hostIntent.putExtra(RuntimeKeys.PROCESS_SLOT, session.spec.processSlot);
                hostIntent.putExtra(RuntimeKeys.PACKAGE_NAME, session.spec.packageName);
                hostIntent.putExtra(RuntimeKeys.VIRTUAL_USER_ID, session.spec.virtualUserId);
                hostIntent.putExtra(RuntimeKeys.PROCESS_NAME, session.spec.processName);
                hostIntent.putExtra(RuntimeKeys.COMPONENT_CLASS, guestClass);
                hostIntent.putExtra(RuntimeKeys.FRAMEWORK_RECEIVER_ROUTE_ID, routeId);
                hostIntent.putExtra(RuntimeKeys.FRAMEWORK_RECEIVER_ENVELOPE, intentEnvelope);
                copyLong(routed, hostIntent, RuntimeKeys.ORDERED_RECEIVER_DEADLINE_MS);
                copyInt(routed, hostIntent, RuntimeKeys.BROADCAST_RESULT_CODE);
                copyString(routed, hostIntent, RuntimeKeys.BROADCAST_RESULT_DATA);
                Bundle initialResultExtras = routed.getBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS);
                if (initialResultExtras != null) {
                    hostIntent.putExtra(RuntimeKeys.BROADCAST_RESULT_EXTRAS,
                            new Bundle(initialResultExtras));
                }
                if (ordered) {
                    session.context.hostServiceContext().sendOrderedBroadcast(hostIntent, null,
                            null, null,
                            routed.getInt(RuntimeKeys.BROADCAST_RESULT_CODE, 0),
                            routed.getString(RuntimeKeys.BROADCAST_RESULT_DATA, ""),
                            initialResultExtras == null ? new Bundle()
                                    : new Bundle(initialResultExtras));
                } else {
                    session.context.hostServiceContext().sendBroadcast(hostIntent);
                }
                return null;
            }, ordered ? RuntimeKeys.FRAMEWORK_RECEIVER_DISPATCH_TIMEOUT_MS
                    : GuestMainThreadDispatcher.DEFAULT_TIMEOUT_MS);
            if (waiter != null) {
                return waiter.await(RuntimeKeys.FRAMEWORK_RECEIVER_DISPATCH_TIMEOUT_MS);
            }
            Bundle result = identityResult(guestClass, routeId);
            result.putBoolean(RuntimeKeys.FRAMEWORK_RECEIVER_ROUTE, true);
            result.putString(RuntimeKeys.STATUS, "BROADCAST_ENQUEUED");
            RuntimeEventLog.event("GUEST_RECEIVER_FRAMEWORK_ENQUEUED", result);
            return result;
        } catch (Throwable error) {
            if (waiter != null) {
                synchronized (receiverWaiterLock) { receiverWaiters.remove(routeId, waiter); }
                waiter.fail(error);
            }
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw error instanceof RuntimeException
                    ? (RuntimeException) error : new IllegalStateException(error);
        }
    }

    Intent registerDynamicReceiver(String receiverId, BroadcastReceiver guestReceiver,
                                   IntentFilter filter, String permission, Handler scheduler,
                                   int flags) {
        if (closed) throw new IllegalStateException("GUEST_RECEIVER_FRAMEWORK_BRIDGE_CLOSED");
        return dynamicTransport.register(receiverId, guestReceiver, filter, permission,
                scheduler, flags);
    }

    void unregisterDynamicReceiver(String receiverId) {
        dynamicTransport.unregister(receiverId);
    }

    private void handleFrameworkReceiver(Object data) throws Exception {
        Intent hostIntent = receiverIntent(data);
        Bundle hostExtras = hostIntent.getExtras();
        Bundle envelope = hostExtras == null ? null
                : hostExtras.getBundle(RuntimeKeys.FRAMEWORK_RECEIVER_ENVELOPE);
        if (envelope == null) throw new SecurityException("FRAMEWORK_RECEIVER_ENVELOPE_MISSING");
        requireCurrentRoute(hostIntent);
        String routeId = hostIntent.getStringExtra(RuntimeKeys.FRAMEWORK_RECEIVER_ROUTE_ID);
        String guestClass = hostIntent.getStringExtra(RuntimeKeys.COMPONENT_CLASS);
        if (routeId == null || routeId.trim().isEmpty()
                || guestClass == null || guestClass.trim().isEmpty()) {
            throw new IllegalArgumentException("FRAMEWORK_RECEIVER_ROUTE_FIELDS_MISSING");
        }
        ReceiverWaiter waiter;
        synchronized (receiverWaiterLock) { waiter = receiverWaiters.remove(routeId); }
        boolean ordered = waiter != null;
        Bundle result = identityResult(guestClass, routeId);
        BroadcastReceiver receiver = null;
        OrderedReceiverPendingResultBridge orderedBridge = null;
        Throwable failure = null;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(GuestDefiningLoader.of(session));
            Intent guestIntent = RuntimeIntentWireCodec.decode(envelope);
            receiver = GuestComponentFactory.instantiateReceiver(
                    GuestDefiningLoader.of(session),
                    GuestApplicationInfoFactory.readComponentFactory(
                            session.context.getApplicationInfo()),
                    guestClass, guestIntent);
            if (receiver == null) {
                throw new IllegalStateException("FRAMEWORK_RECEIVER_FACTORY_RETURNED_NULL");
            }
            if (ordered) {
                orderedBridge = installOrderedPendingResult(receiver, waiter, hostIntent, hostExtras,
                        guestClass);
            } else {
                setPendingResult(receiver, data);
            }
            receiver.onReceive(session.context, guestIntent);
            if (ordered) {
                result.putAll(orderedBridge.afterOnReceive());
                finishReceiverData(data);
            } else {
                Object pending = pendingResult(receiver);
                if (pending != null) {
                    copyReceiverResult(result, data);
                    finishReceiverData(data);
                } else {
                    result.putBoolean(RuntimeKeys.BROADCAST_PENDING_ASYNC, true);
                }
            }
            result.putString(RuntimeKeys.STATUS, "BROADCAST_DELIVERED");
            result.putString(ComponentOperations.ACTION,
                    guestIntent.getAction() == null ? "" : guestIntent.getAction());
            result.putBoolean(RuntimeKeys.FRAMEWORK_RECEIVER_ROUTE, true);
            RuntimeEventLog.event("GUEST_RECEIVER_FRAMEWORK_DELIVERED", result);
            android.util.Log.i("CS_RECEIVER_FRAMEWORK", "RECEIVER guest=" + guestClass
                    + " action=" + guestIntent.getAction() + " process=" + session.spec.processName);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            failure = error;
            if (orderedBridge != null) {
                try { orderedBridge.cancelLocal(); }
                catch (Throwable cancelFailure) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(
                            cancelFailure);
                }
            }
            try { finishReceiverData(data); }
            catch (Throwable finishFailure) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(
                        finishFailure);
            }
            result.putString(RuntimeKeys.STATUS, "FAILED");
            result.putString(RuntimeKeys.ERROR_TYPE, error.getClass().getSimpleName());
            result.putString(RuntimeKeys.ERROR_MESSAGE,
                    error.getMessage() == null ? "" : error.getMessage());
            RuntimeEventLog.event("GUEST_RECEIVER_FRAMEWORK_FAILED", result);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            if (receiver != null) clearPendingResult(receiver);
        }
        if (waiter != null) waiter.complete(result);
    }

    private OrderedReceiverPendingResultBridge installOrderedPendingResult(
            BroadcastReceiver receiver, ReceiverWaiter waiter, Intent hostIntent,
            Bundle hostExtras, String guestClass) throws Exception {
        Bundle request = new Bundle();
        request.putString(RuntimeKeys.ORDERED_RECEIVER_TOKEN, waiter.orderedToken);
        request.putBinder(RuntimeKeys.ORDERED_RECEIVER_COMPLETION_BINDER,
                waiter.completionBinder);
        request.putString(RuntimeKeys.PACKAGE_NAME, session.spec.packageName);
        request.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.spec.virtualUserId);
        request.putString(RuntimeKeys.SESSION_ID, session.spec.sessionId);
        request.putLong(RuntimeKeys.GENERATION, session.spec.generation);
        request.putString(RuntimeKeys.COMPONENT_CLASS, guestClass);
        request.putLong(RuntimeKeys.ORDERED_RECEIVER_DEADLINE_MS,
                hostIntent.getLongExtra(RuntimeKeys.ORDERED_RECEIVER_DEADLINE_MS, -1L));
        request.putInt(RuntimeKeys.BROADCAST_RESULT_CODE,
                hostIntent.getIntExtra(RuntimeKeys.BROADCAST_RESULT_CODE, 0));
        request.putString(RuntimeKeys.BROADCAST_RESULT_DATA,
                hostIntent.getStringExtra(RuntimeKeys.BROADCAST_RESULT_DATA));
        Bundle initialExtras = hostExtras.getBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS);
        request.putBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS,
                initialExtras == null ? new Bundle() : new Bundle(initialExtras));
        return OrderedReceiverPendingResultBridge.install(receiver, request,
                session.orderedReceiverFinishInterceptor);
    }

    private void requireCurrentRoute(Intent intent) {
        String sessionId = intent.getStringExtra(RuntimeKeys.SESSION_ID);
        long generation = intent.getLongExtra(RuntimeKeys.GENERATION, -1L);
        int slot = intent.getIntExtra(RuntimeKeys.PROCESS_SLOT, -1);
        if (!session.spec.sessionId.equals(sessionId) || session.spec.generation != generation
                || session.spec.processSlot != slot) {
            throw new SecurityException("FRAMEWORK_RECEIVER_ROUTE_GENERATION_MISMATCH");
        }
    }

    private Bundle identityResult(String guestClass, String routeId) {
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.SESSION_ID, session.spec.sessionId);
        result.putLong(RuntimeKeys.GENERATION, session.spec.generation);
        result.putInt(RuntimeKeys.PROCESS_SLOT, session.spec.processSlot);
        result.putString(RuntimeKeys.PACKAGE_NAME, session.spec.packageName);
        result.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.spec.virtualUserId);
        result.putString(RuntimeKeys.PROCESS_NAME, session.spec.processName);
        if (guestClass != null && !guestClass.isEmpty()) {
            result.putString(RuntimeKeys.COMPONENT_CLASS, guestClass);
        }
        if (routeId != null && !routeId.isEmpty()) {
            result.putString(RuntimeKeys.FRAMEWORK_RECEIVER_ROUTE_ID, routeId);
        }
        return result;
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

    private static void clearPendingResult(BroadcastReceiver receiver) {
        try {
            Class<?> type = Class.forName("android.content.BroadcastReceiver$PendingResult");
            BroadcastReceiver.class.getMethod("setPendingResult", type).invoke(receiver,
                    new Object[]{null});
        } catch (Throwable ignored) {
            // The host framework clears the ReceiverData after the delivery acknowledgement.
        }
    }

    private static void copyReceiverResult(Bundle out, Object data) {
        try {
            out.putInt(RuntimeKeys.BROADCAST_RESULT_CODE,
                    ((Number) data.getClass().getMethod("getResultCode").invoke(data)).intValue());
            Object value = data.getClass().getMethod("getResultData").invoke(data);
            out.putString(RuntimeKeys.BROADCAST_RESULT_DATA, value == null ? "" : String.valueOf(value));
            Object extras = data.getClass().getMethod("getResultExtras", boolean.class)
                    .invoke(data, false);
            if (extras instanceof Bundle) {
                out.putBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS, new Bundle((Bundle) extras));
            }
            boolean aborted = Boolean.TRUE.equals(
                    data.getClass().getMethod("getAbortBroadcast").invoke(data));
            out.putBoolean(RuntimeKeys.BROADCAST_ABORTED, aborted);
            out.putBoolean(RuntimeKeys.BROADCAST_ABORT, aborted);
        } catch (ReflectiveOperationException ignored) {
            // OEM ReceiverData shapes may omit result accessors; delivery remains valid.
        }
    }

    private static void finishReceiverData(Object data) throws Exception {
        Method finish = data.getClass().getMethod("finish");
        finish.setAccessible(true);
        finish.invoke(data);
    }

    private static Intent receiverIntent(Object data) {
        Object value = field(data, "intent");
        return value instanceof Intent ? (Intent) value : null;
    }

    private static boolean isFrameworkReceiverRoute(Intent intent) {
        return intent != null && intent.getBooleanExtra(RuntimeKeys.FRAMEWORK_RECEIVER_ROUTE, false);
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                throw new IllegalStateException("RECEIVER_FIELD_UNAVAILABLE:" + name, error);
            }
        }
        throw new IllegalStateException("RECEIVER_FIELD_UNAVAILABLE:" + name);
    }

    private static void copyString(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) target.putExtra(key, source.getString(key));
    }

    private static void copyInt(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) target.putExtra(key, source.getInt(key));
    }

    private static void copyLong(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) target.putExtra(key, source.getLong(key));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        dynamicTransport.close();
        synchronized (receiverWaiterLock) {
            for (ReceiverWaiter waiter : receiverWaiters.values()) {
                waiter.fail(new IllegalStateException("GUEST_RECEIVER_FRAMEWORK_BRIDGE_CLOSED"));
            }
            receiverWaiters.clear();
        }
    }

    private static final class ReceiverWaiter {
        final String routeId;
        final String component;
        final String orderedToken;
        final IBinder completionBinder;
        final CountDownLatch completed = new CountDownLatch(1);
        volatile Bundle result;
        volatile Throwable failure;

        ReceiverWaiter(String routeId, String component, String orderedToken,
                       IBinder completionBinder) {
            this.routeId = routeId;
            this.component = component;
            this.orderedToken = orderedToken == null ? "" : orderedToken;
            this.completionBinder = completionBinder;
        }

        void complete(Bundle result) {
            this.result = result == null ? new Bundle() : new Bundle(result);
            completed.countDown();
        }

        void fail(Throwable failure) {
            this.failure = failure;
            completed.countDown();
        }

        Bundle await(long timeoutMs) {
            try {
                if (!completed.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("FRAMEWORK_RECEIVER_TIMEOUT:" + component);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("FRAMEWORK_RECEIVER_INTERRUPTED:" + component,
                        error);
            }
            if (failure != null) {
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(failure);
            }
            return result == null ? new Bundle() : new Bundle(result);
        }
    }

}
