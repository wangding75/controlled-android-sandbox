package com.warden.controlledsandbox.runtime.guest;

import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;

import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.runtime.component.service.GuestServiceStubNames;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.nativebridge.NativePolicy;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** ActivityThread-owned Service lifecycle and token transport for one Guest process. */
final class GuestActivityThreadServiceLifecycle implements AutoCloseable {
    private static final int CREATE_SERVICE = 114;
    private static final int SERVICE_ARGS = 115;
    private static final int STOP_SERVICE = 116;
    private static final int BIND_SERVICE = 121;
    private static final int UNBIND_SERVICE = 122;
    private static final int SERVICE_DONE_EXECUTING_ANON = 0;
    private static final int SERVICE_DONE_EXECUTING_START = 1;
    private static final int SERVICE_DONE_EXECUTING_STOP = 2;
    private static final int SERVICE_DONE_EXECUTING_UNBIND = 3;
    private static final int START_TASK_REMOVED_COMPLETE = 1000;

    private final GuestRuntimeEnvironment.Session session;
    private final Object activityThread;
    private final Class<?> activityThreadType;
    private final Object servicesData;
    private final Object services;
    private final Object activityManager;
    private final GuestRuntimeBrokerBridge routeBroker;
    private final Map<IBinder, Record> records = new IdentityHashMap<>();
    private final GuestServiceForegroundTransport foregroundTransport;
    private final Map<String, RecoveryWaiter> recoveryWaiters = new HashMap<>();
    private final Map<String, StopWaiter> stopWaiters = new HashMap<>();
    private volatile boolean closed;

    GuestActivityThreadServiceLifecycle(GuestRuntimeEnvironment.Session session,
                                        Object activityThread, Object servicesData,
                                        Object services, Object activityManager,
                                        GuestRuntimeBrokerBridge routeBroker) {
        if (session == null || activityThread == null || routeBroker == null) {
            throw new IllegalArgumentException("service lifecycle dependencies are required");
        }
        this.session = session;
        this.activityThread = activityThread;
        this.activityThreadType = activityThread.getClass();
        this.servicesData = servicesData;
        this.services = services;
        this.activityManager = activityManager;
        this.routeBroker = routeBroker;
        this.foregroundTransport = new GuestServiceForegroundTransport(session, activityManager,
                routeBroker);
    }

    boolean handle(Message message) throws Exception {
        if (closed || message == null) return false;
        if (message.what == CREATE_SERVICE && isFrameworkRoute(createIntent(message.obj))) {
            createService(message.obj);
            return true;
        }
        IBinder token = tokenFor(message.what, message.obj);
        Record record;
        synchronized (records) { record = token == null ? null : records.get(token); }
        if (record == null && token != null
                && (message.what == BIND_SERVICE || message.what == SERVICE_ARGS)) {
            Intent routeIntent = lifecycleIntent(message.what, message.obj);
            if (isFrameworkRoute(routeIntent)) {
                promoteCreatedService(token, routeIntent);
                synchronized (records) { record = records.get(token); }
            }
        }
        if (record == null) return false;
        switch (message.what) {
            case BIND_SERVICE -> bindService(message.obj, record);
            case UNBIND_SERVICE -> unbindService(message.obj, record);
            case SERVICE_ARGS -> serviceArgs(message.obj, record);
            case STOP_SERVICE -> stopService(token, record);
            default -> { return false; }
        }
        return true;
    }

    Bundle recover(Bundle request, String guestClass, RecoveryStarter starter) {
        if (closed) throw new IllegalStateException("GUEST_SERVICE_FRAMEWORK_BRIDGE_CLOSED");
        if (guestClass == null || guestClass.trim().isEmpty()) {
            throw new IllegalArgumentException("FRAMEWORK_SERVICE_COMPONENT_MISSING");
        }
        RecoveryWaiter waiter = new RecoveryWaiter(guestClass);
        synchronized (recoveryWaiters) {
            if (recoveryWaiters.put(guestClass, waiter) != null) {
                throw new IllegalStateException("FRAMEWORK_SERVICE_RECOVERY_ALREADY_PENDING:" + guestClass);
            }
        }
        try {
            session.mainThread.call(starter::start);
            return waiter.await(GuestMainThreadDispatcher.DEFAULT_TIMEOUT_MS);
        } catch (Throwable error) {
            synchronized (recoveryWaiters) { recoveryWaiters.remove(guestClass, waiter); }
            waiter.fail(error);
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw error instanceof RuntimeException
                    ? (RuntimeException) error : new IllegalStateException(error);
        }
    }

    /**
     * Registers the framework stop acknowledgement before asking AMS to stop the StubService.
     * Context.stopService() returns when the request is accepted, not when ActivityThread has
     * completed Service.onDestroy() and serviceDoneExecuting().  A following start can otherwise
     * reuse a still-destroying host ServiceRecord and never deliver CREATE_SERVICE/SERVICE_ARGS.
     */
    StopWaiter prepareStop(String guestClass) {
        if (guestClass == null || guestClass.trim().isEmpty()) {
            throw new IllegalArgumentException("FRAMEWORK_SERVICE_COMPONENT_MISSING");
        }
        synchronized (stopWaiters) {
            if (stopWaiters.containsKey(guestClass)) {
                throw new IllegalStateException("FRAMEWORK_SERVICE_STOP_ALREADY_PENDING:" + guestClass);
            }
            StopWaiter waiter = new StopWaiter();
            stopWaiters.put(guestClass, waiter);
            return waiter;
        }
    }

    void cancelStop(String guestClass, StopWaiter waiter) {
        synchronized (stopWaiters) {
            if (stopWaiters.get(guestClass) == waiter) stopWaiters.remove(guestClass);
        }
        waiter.done.countDown();
    }

    void awaitStop(String guestClass, StopWaiter waiter) throws Exception {
        if (!waiter.done.await(GuestMainThreadDispatcher.DEFAULT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS)) {
            synchronized (stopWaiters) {
                if (stopWaiters.get(guestClass) == waiter) stopWaiters.remove(guestClass);
            }
            throw new IllegalStateException("FRAMEWORK_SERVICE_STOP_TIMEOUT:" + guestClass);
        }
    }

    /**
     * Serves a Broker-side binding against the Service instance owned by ActivityThread.
     *
     * <p>RuntimeClient's lifecycle probe is not an Android {@link ServiceConnection}; it still
     * must observe the same Service object as the framework callbacks.  Sending that operation
     * through {@link GuestComponentRuntime}'s legacy map would create a second Service instance
     * and split started/bound ownership.  Keep these leases in a separate filtered-Intent ledger
     * so real AMS bindings remain independent while both paths share the framework Service object.</p>
     */
    Bundle bindForBroker(Bundle request, String guestClass) throws Exception {
        Record record = requireRecordForBroker(guestClass);
        String connectionId = required(request, RuntimeKeys.CONNECTION_ID);
        if (record.brokerConnectionIntents.containsKey(connectionId)) {
            throw new IllegalStateException("DUPLICATE_SERVICE_CONNECTION");
        }
        RuntimeIntentWireCodec.materializePayloadForBroker(request);
        Intent intent = RuntimeIntentWireCodec.decode(request);
        FrameworkServiceBindingLedger.Entry binding;
        boolean rebound = false;
        binding = record.brokerBindings.takePendingRebind(intent);
        if (binding != null) {
            record.service.onRebind(intent);
            rebound = true;
        } else {
            binding = record.brokerBindings.bind(intent, record.service::onBind);
        }
        record.brokerConnectionIntents.put(connectionId, intent);
        record.lastBinder = binding.binder();
        Bundle out = frameworkResult(record, binding.binder() == null
                ? "SERVICE_NULL_BINDING" : "SERVICE_BOUND");
        out.putString(RuntimeKeys.CONNECTION_ID, connectionId);
        out.putInt("connectionCount", record.brokerConnectionIntents.size());
        out.putBoolean("rebound", rebound);
        if (binding.binder() != null) out.putBinder(RuntimeKeys.BINDER, binding.binder());
        RuntimeEventLog.event("GUEST_SERVICE_FRAMEWORK_BROKER_BOUND", out);
        return out;
    }

    /** Releases one Broker-side binding without constructing a legacy Service record. */
    Bundle unbindForBroker(Bundle request, String guestClass) throws Exception {
        Record record = requireRecordForBroker(guestClass);
        String connectionId = required(request, RuntimeKeys.CONNECTION_ID);
        Intent intent = record.brokerConnectionIntents.get(connectionId);
        if (intent == null) throw new IllegalArgumentException("UNKNOWN_SERVICE_CONNECTION");
        FrameworkServiceBindingLedger.Entry binding = record.brokerBindings.find(intent);
        if (binding == null) throw new IllegalStateException("FRAMEWORK_SERVICE_BINDING_MISSING");
        boolean lastClient = binding.bindCount() <= 1;
        boolean rebind = lastClient && record.service.onUnbind(intent);
        FrameworkServiceBindingLedger.UnbindResult unbound =
                record.brokerBindings.unbindAndReport(intent, rebind);
        record.brokerConnectionIntents.remove(connectionId);
        Bundle out = frameworkResult(record, "SERVICE_UNBOUND");
        out.putString(RuntimeKeys.CONNECTION_ID, connectionId);
        out.putInt("connectionCount", record.brokerConnectionIntents.size());
        out.putBoolean("rebindRequested", unbound.rebindPending());
        RuntimeEventLog.event("GUEST_SERVICE_FRAMEWORK_BROKER_UNBOUND", out);
        return out;
    }

    /** Mirrors Service.stopSelfResult for Broker callers while preserving the framework owner. */
    Bundle stopStartIdForBroker(Bundle request, String guestClass) throws Exception {
        Record record = requireRecordForBroker(guestClass);
        int startId = request.getInt(RuntimeKeys.SERVICE_START_ID, -1);
        if (startId < 1) throw new IllegalArgumentException("serviceStartId must be positive");
        boolean stopped = startId == record.lastStartId;
        if (stopped && !record.service.stopSelfResult(startId)) {
            throw new IllegalStateException("FRAMEWORK_SERVICE_STOP_START_ID_REJECTED");
        }
        Bundle out = frameworkResult(record, stopped
                ? "SERVICE_STOPPED_BY_START_ID" : "SERVICE_START_ID_STALE");
        out.putInt(RuntimeKeys.SERVICE_START_ID, startId);
        out.putInt(RuntimeKeys.SERVICE_LAST_START_ID, record.lastStartId);
        out.putBoolean(RuntimeKeys.SERVICE_STOPPED_BY_START_ID, stopped);
        out.putInt("connectionCount", record.brokerConnectionIntents.size());
        RuntimeEventLog.event("GUEST_SERVICE_FRAMEWORK_BROKER_STOP_START_ID", out);
        return out;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        synchronized (records) {
            for (Record record : new ArrayList<>(records.values())) {
                try { record.service.onDestroy(); } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
                try { detach(record.service); } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
                foregroundTransport.clear(record.token);
                try { serviceDone(record.token, SERVICE_DONE_EXECUTING_STOP, 0, 0); }
                catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
                record.bindings.clear();
                record.brokerBindings.clear();
                record.brokerConnectionIntents.clear();
                mapRemove(servicesData, record.token);
                mapRemove(services, record.token);
            }
            records.clear();
        }
        foregroundTransport.close();
        synchronized (recoveryWaiters) {
            for (RecoveryWaiter waiter : recoveryWaiters.values()) {
                waiter.fail(new IllegalStateException("GUEST_SERVICE_FRAMEWORK_BRIDGE_CLOSED"));
            }
            recoveryWaiters.clear();
        }
        synchronized (stopWaiters) {
            for (StopWaiter waiter : stopWaiters.values()) waiter.done.countDown();
            stopWaiters.clear();
        }
    }

    @FunctionalInterface
    interface RecoveryStarter {
        ComponentName start() throws Exception;
    }

    private void createService(Object data) throws Exception {
        Intent hostIntent = createIntent(data);
        String sessionId = hostIntent.getStringExtra(RuntimeKeys.SESSION_ID);
        long generation = hostIntent.getLongExtra(RuntimeKeys.GENERATION, -1L);
        if (!session.spec.sessionId.equals(sessionId) || session.spec.generation != generation) {
            throw new SecurityException("FRAMEWORK_SERVICE_ROUTE_GENERATION_MISMATCH");
        }
        String guestClass = hostIntent.getStringExtra(RuntimeKeys.COMPONENT_CLASS);
        if (guestClass == null || guestClass.trim().isEmpty()) {
            throw new IllegalArgumentException("FRAMEWORK_SERVICE_COMPONENT_MISSING");
        }
        IBinder token = (IBinder) field(data, "token");
        Intent guestIntent = decodeGuestIntent(hostIntent);
        installGuestService(data, token, hostIntent, false);
    }

    /**
     * API32's CREATE_SERVICE packet has no start Intent. The first BIND_SERVICE/SERVICE_ARGS
     * packet does carry our route, so replace the framework-created Stub object at that token
     * before dispatching the actual Guest callback.
     */
    private void promoteCreatedService(IBinder token, Intent hostIntent) throws Exception {
        Object data = mapGet(servicesData, token);
        if (data == null) throw new IllegalStateException("FRAMEWORK_SERVICE_CREATE_DATA_MISSING");
        installGuestService(data, token, hostIntent, true);
    }

    private void installGuestService(Object data, IBinder token, Intent hostIntent,
                                     boolean replaceExisting) throws Exception {
        String sessionId = hostIntent.getStringExtra(RuntimeKeys.SESSION_ID);
        long generation = hostIntent.getLongExtra(RuntimeKeys.GENERATION, -1L);
        if (!session.spec.sessionId.equals(sessionId) || session.spec.generation != generation) {
            throw new SecurityException("FRAMEWORK_SERVICE_ROUTE_GENERATION_MISMATCH");
        }
        String guestClass = hostIntent.getStringExtra(RuntimeKeys.COMPONENT_CLASS);
        if (guestClass == null || guestClass.trim().isEmpty()) {
            throw new IllegalArgumentException("FRAMEWORK_SERVICE_COMPONENT_MISSING");
        }
        if (replaceExisting) {
            Object old = mapGet(services, token);
            foregroundTransport.clear(token);
            if (old instanceof Service) {
                try { ((Service) old).onDestroy(); } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
                try { detach((Service) old); } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
            }
        }
        Intent guestIntent = decodeGuestIntent(hostIntent);
        ClassLoader definingLoader = GuestDefiningLoader.of(session);
        GuestDefiningLoader.loadComponent(session, guestClass);
        Service service = GuestComponentFactory.instantiateService(definingLoader,
                GuestApplicationInfoFactory.readComponentFactory(
                        session.context.getApplicationInfo()), guestClass, guestIntent);
        if (service == null) throw new IllegalStateException("FRAMEWORK_SERVICE_FACTORY_RETURNED_NULL");
        if (!(service instanceof Service)) throw new IllegalArgumentException("NOT_A_GUEST_SERVICE:" + guestClass);
        ServiceInfo projected = projectServiceInfo(data, guestClass);
        VirtualPackageMetadata.Component projectedComponent = session.packageMetadata.component(
                guestClass, VirtualPackageMetadata.Type.SERVICE);
        if (projectedComponent == null) {
            throw new IllegalArgumentException("SERVICE_NOT_DECLARED:" + guestClass);
        }
        setField(data, "info", projected);
        Object manager = foregroundTransport.serviceManagerProxy(projected, token,
                hostIntent.getBooleanExtra(RuntimeKeys.SERVICE_RECOVERY, false));
        Method attach = Service.class.getDeclaredMethod("attach", Context.class,
                activityThreadType, String.class, IBinder.class, Application.class, Object.class);
        attach.setAccessible(true);
        attach.invoke(service, session.context, activityThread, guestClass, token,
                session.application, manager);
        service.onCreate();
        Bundle storedRoute = routeBundle(hostIntent);
        // The Guest manifest projection is authoritative for FGS type validation.  Carry it on
        // the framework record because Service.startForeground() can run before the final
        // onStartCommand result is known.
        // ServiceInfo.foregroundServiceType is hidden on some API/OEM builds.  The immutable
        // Guest manifest projection is authoritative even when that framework field cannot be
        // written reflectively.
        storedRoute.putInt(RuntimeKeys.SERVICE_FOREGROUND_DECLARED_TYPE_MASK,
                projectedComponent.foregroundServiceType());
        synchronized (records) {
            records.put(token, new Record(token, service, guestClass, projected,
                    new ComponentName(session.context.hostServiceContext(),
                            GuestServiceStubNames.classNameFor(session.spec.processSlot)),
                    storedRoute));
        }
        foregroundTransport.initialize(token);
        mapPut(servicesData, token, data);
        mapPut(services, token, service);
        if (!replaceExisting) serviceDone(token, SERVICE_DONE_EXECUTING_ANON, 0, 0);
        Bundle event = new Bundle();
        event.putString(RuntimeKeys.STATUS, "FRAMEWORK_SERVICE_CREATED");
        event.putBoolean("frameworkOwnedService", true);
        event.putString(RuntimeKeys.COMPONENT_CLASS, guestClass);
        event.putString(RuntimeKeys.SESSION_ID, session.spec.sessionId);
        event.putLong(RuntimeKeys.GENERATION, session.spec.generation);
        event.putInt(RuntimeKeys.PROCESS_SLOT, session.spec.processSlot);
        event.putString("definingLoader", definingLoader.getClass().getName());
        event.putString("token", String.valueOf(token));
        RuntimeEventLog.event("GUEST_SERVICE_FRAMEWORK_CREATED", event);
        android.util.Log.i("CS_SERVICE_FRAMEWORK", (replaceExisting ? "PROMOTE_SERVICE" : "CREATE_SERVICE")
                + " guest=" + guestClass + " process=" + session.spec.processName + " token=" + token);
    }

    private void bindService(Object data, Record record) throws Exception {
        Intent hostIntent = (Intent) field(data, "intent");
        Intent intent = decodeGuestIntent(hostIntent);
        boolean rebind = booleanField(data, "rebind");
        FrameworkServiceBindingLedger.Entry binding;
        if (rebind) {
            binding = record.bindings.takePendingRebind(intent);
            if (binding != null) {
                record.service.onRebind(intent);
                serviceDone(record.token, SERVICE_DONE_EXECUTING_ANON, 0, 0);
            } else {
                // Be tolerant of an OEM/framework that marks a first bind as rebind.  The
                // authoritative record is still created per filtered Intent; only the callback
                // falls back to onBind when no prior onUnbind(true) exists.
                binding = record.bindings.bind(intent, record.service::onBind);
                publishService(record.token, hostIntent, binding.binder());
            }
        } else {
            binding = record.bindings.bind(intent, record.service::onBind);
            publishService(record.token, hostIntent, binding.binder());
        }
        record.lastBinder = binding.binder();
        recordFrameworkEvent(record, lifecycleRoute(record, hostIntent), ComponentOperations.FRAMEWORK_SERVICE_EVENT_BIND,
                0, 0);
        logLifecycle("GUEST_SERVICE_FRAMEWORK_BOUND", record, rebind ? "REBIND" : "BIND");
    }

    private void unbindService(Object data, Record record) throws Exception {
        Intent hostIntent = (Intent) field(data, "intent");
        Intent intent = decodeGuestIntent(hostIntent);
        FrameworkServiceBindingLedger.Entry binding = record.bindings.find(intent);
        if (binding == null) {
            // A duplicate/OEM late UNBIND_SERVICE must still acknowledge the framework message,
            // but it must never call Service.onUnbind(): Android only delivers that callback for
            // the final live binding record.  Calling it for an unknown intent corrupts
            // doRebind state and can destroy a Service that still has another client.
            unbindFinished(record.token, hostIntent, false);
            android.util.Log.w("CS_SERVICE_FRAMEWORK",
                    "ignored unbind for unknown filtered Intent component=" + record.className);
            logLifecycle("GUEST_SERVICE_FRAMEWORK_UNBIND_IGNORED", record, "UNKNOWN_INTENT");
            return;
        }
        // The Service callback is per filtered interface, not per client.  A framework/OEM
        // route can still emit one UNBIND_SERVICE message for each client, so only the final
        // ledger reference may call Guest Service.onUnbind().
        boolean lastClient = binding.bindCount() <= 1;
        boolean rebind = lastClient && record.service.onUnbind(intent);
        FrameworkServiceBindingLedger.UnbindResult unbound =
                record.bindings.unbindAndReport(intent, rebind);
        // Android 15 split the completion contract: a rebind request still uses
        // unbindFinished(), while an ordinary onUnbind(false) uses the API35
        // serviceDoneExecuting(..., serviceIntent) transaction.  Keep the pre-35
        // acknowledgement unchanged because those framework contracts use unbindFinished()
        // for both branches.
        boolean rebindRequested = unbound.lastClient() && unbound.rebindPending();
        if (Build.VERSION.SDK_INT >= 35 && !rebindRequested) {
            serviceDone(record.token, SERVICE_DONE_EXECUTING_UNBIND, 0, 0, hostIntent);
        } else {
            unbindFinished(record.token, hostIntent, rebindRequested);
        }
        recordFrameworkEvent(record, lifecycleRoute(record, hostIntent), ComponentOperations.FRAMEWORK_SERVICE_EVENT_UNBIND,
                0, 0);
        logLifecycle("GUEST_SERVICE_FRAMEWORK_UNBOUND", record, rebind ? "REBIND" : "UNBIND");
    }

    private void serviceArgs(Object data, Record record) throws Exception {
        int startId = intField(data, "startId");
        int flags = intField(data, "flags");
        Bundle route = lifecycleRoute(record, (Intent) field(data, "args"));
        boolean taskRemoved = booleanField(data, "taskRemoved");
        boolean recovery = route.getBoolean(RuntimeKeys.SERVICE_RECOVERY, false);
        boolean redelivered = route.getBoolean(RuntimeKeys.SERVICE_REDELIVERED, false);
        // ActivityThread uses a null Intent for START_STICKY restarts.  Preserve that distinction
        // at the framework-owned callback boundary; only START_REDELIVER_INTENT gets the retained
        // wire payload restored by the Broker.
        Intent intent = recovery && !redelivered
                ? null : decodeGuestIntent((Intent) field(data, "args"));
        if (!taskRemoved && !recovery) {
            record.lastStartId = startId;
            record.startCount++;
            // Service.onStartCommand may synchronously call startForeground.  Register the
            // virtual started record first, then commit the callback's restart mode below.
            recordFrameworkEvent(record, route,
                    ComponentOperations.FRAMEWORK_SERVICE_EVENT_START_BEGIN,
                    startId, Service.START_NOT_STICKY);
        }
        int result;
        if (taskRemoved) {
            // This is a distinct Service callback in the platform contract.  Calling
            // onStartCommand() here loses the task-removal signal used by sticky services.
            record.service.onTaskRemoved(intent);
            result = START_TASK_REMOVED_COMPLETE;
        } else {
            result = record.service.onStartCommand(intent, flags, startId);
            if (session.nativeHooksInstalled) {
                boolean refreshed = NativePolicy.refreshHooks();
                android.util.Log.i("CS_NATIVE_HOOK", "REFRESH stage=FRAMEWORK_SERVICE_START refreshed="
                        + refreshed + " status=" + NativePolicy.hookStatus());
                if (!refreshed) {
                    throw new IllegalStateException("NATIVE_FILE_HOOK_REFRESH_FAILED_FRAMEWORK_SERVICE_START:"
                            + NativePolicy.hookStatus());
                }
            }
        }
        serviceDone(record.token, SERVICE_DONE_EXECUTING_START, startId, result);
        if (!completeFrameworkRecovery(record, route, startId, result)) {
            if (!taskRemoved) {
                recordFrameworkEvent(record, route, ComponentOperations.FRAMEWORK_SERVICE_EVENT_START,
                        startId, result);
            }
        }
        logLifecycle("GUEST_SERVICE_FRAMEWORK_STARTED", record, "START:" + startId);
    }

    /** Commits an ActivityThread lifecycle edge without constructing a second Guest Service. */
    private void recordFrameworkEvent(Record record, Bundle route, String event,
                                      int startId, int startResult) {
        Bundle request = session.spec.toRuntimeRequestBundle();
        if (route != null) request.putAll(route);
        request.putString(ComponentOperations.OPERATION,
                ComponentOperations.FRAMEWORK_SERVICE_EVENT);
        request.putString(RuntimeKeys.FRAMEWORK_SERVICE_EVENT, event);
        request.putString(RuntimeKeys.PACKAGE_NAME, session.spec.packageName);
        request.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.spec.virtualUserId);
        request.putString(RuntimeKeys.PROCESS_NAME, session.spec.processName);
        request.putString(RuntimeKeys.COMPONENT_CLASS, record.className);
        request.putString(RuntimeKeys.TARGET_PACKAGE_NAME, session.spec.packageName);
        request.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        if (ComponentOperations.FRAMEWORK_SERVICE_EVENT_START.equals(event)
                || ComponentOperations.FRAMEWORK_SERVICE_EVENT_START_BEGIN.equals(event)) {
            request.putInt(RuntimeKeys.SERVICE_START_ID, startId);
            request.putInt(RuntimeKeys.SERVICE_START_RESULT, startResult);
        }
        // The full Intent is retained in this process only long enough to reconstruct the Guest
        // callback.  The lifecycle commit is another Guest -> Broker Binder edge, so reproduce
        // the VA/NBB boundary: send a fresh bounded descriptor, never the byte[] or a duplicate
        // extras Bundle.  The Broker consumes it into its own service record before state update.
        byte[] intentPayload = RuntimeIntentWireCodec.routePayload(request);
        if (intentPayload != null) {
            RuntimeIntentWireCodec.attachRoutePayloadDescriptor(request, intentPayload);
        }
        try {
            Bundle result = routeBroker.invokeComponent(request);
            if (result == null || "FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
                throw new IllegalStateException("FRAMEWORK_SERVICE_EVENT_REJECTED:" + event + ":"
                        + (result == null ? "NO_RESULT" : result.getString(RuntimeKeys.ERROR_TYPE, "FAILED")));
            }
            RuntimeEventLog.event("GUEST_SERVICE_FRAMEWORK_EVENT_RECORDED", result);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_SERVICE_FRAMEWORK", "Broker lifecycle commit failed event=" + event,
                    error);
            throw error instanceof RuntimeException
                    ? (RuntimeException) error : new IllegalStateException(error);
        }
    }

    private boolean completeFrameworkRecovery(Record record, Bundle route,
                                              int startId, int startResult) {
        boolean recovery = record.routeRequest.getBoolean(RuntimeKeys.SERVICE_RECOVERY, false)
                || (route != null && route.getBoolean(RuntimeKeys.SERVICE_RECOVERY, false));
        if (!recovery) return false;
        RecoveryWaiter waiter;
        synchronized (recoveryWaiters) { waiter = recoveryWaiters.remove(record.className); }
        if (waiter == null) {
            throw new IllegalStateException("FRAMEWORK_SERVICE_RECOVERY_WAITER_MISSING:" + record.className);
        }
        Bundle result = new Bundle();
        // Recovery is a cross-thread callback: the result must carry the current
        // process identity before it is both logged and committed by the Broker.
        // Without this, a successful callback was observable as generation=0 and
        // processSlot=-1 even though the subsequent registry commit used the new
        // generation. That makes stale-callback audits ambiguous.
        result.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        result.putLong(RuntimeKeys.GENERATION, session.generation());
        result.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        result.putString(RuntimeKeys.PACKAGE_NAME, session.packageName());
        result.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.virtualUserId());
        result.putString(RuntimeKeys.PROCESS_NAME, session.spec.processName);
        result.putString(RuntimeKeys.STATUS, "FRAMEWORK_SERVICE_RECOVERED");
        result.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        result.putString(RuntimeKeys.COMPONENT_CLASS, record.className);
        result.putInt(RuntimeKeys.SERVICE_START_ID, startId);
        result.putInt(RuntimeKeys.SERVICE_START_RESULT, startResult);
        result.putInt("onStartCommandResult", startResult);
        result.putBoolean(RuntimeKeys.SERVICE_REDELIVERED,
                route != null && route.getBoolean(RuntimeKeys.SERVICE_REDELIVERED, false));
        Bundle foregroundSnapshot = foregroundTransport.recoverySnapshot(record.token);
        if (foregroundSnapshot != null) {
            // The promotion crossed real AMS while the stale generation was still fenced.  The
            // Broker commits these values only after completeFrameworkRecovery advances the
            // generation, so no stale Binder can promote the old record.
            result.putAll(foregroundSnapshot);
        }
        waiter.complete(result);
        RuntimeEventLog.event("GUEST_SERVICE_FRAMEWORK_RECOVERED", result);
        return true;
    }

    private void stopService(IBinder token, Record record) throws Exception {
        try {
            synchronized (records) { records.remove(token); }
            foregroundTransport.clear(token);
            record.bindings.clear();
            record.brokerBindings.clear();
            record.brokerConnectionIntents.clear();
            mapRemove(servicesData, token);
            mapRemove(services, token);
            record.service.onDestroy();
            detach(record.service);
            // ActivityThread.handleStopService acknowledges STOP_SERVICE to AMS after
            // Service.onDestroy(). The bridge owns that callback, so omitting the
            // STOP acknowledgement leaves the StubService in AMS's executing set and
            // produces a delayed ANR even though the guest Service has already been
            // destroyed. VA/NBB preserve this framework completion edge as well.
            serviceDone(token, SERVICE_DONE_EXECUTING_STOP, 0, 0);
            recordFrameworkEvent(record, record.routeRequest,
                    ComponentOperations.FRAMEWORK_SERVICE_EVENT_STOP, 0, 0);
            logLifecycle("GUEST_SERVICE_FRAMEWORK_DESTROYED", record, "STOP");
        } finally {
            signalStop(record.className);
        }
    }

    private void signalStop(String guestClass) {
        StopWaiter waiter;
        synchronized (stopWaiters) { waiter = stopWaiters.remove(guestClass); }
        if (waiter != null) waiter.done.countDown();
    }

    private Record requireRecordForBroker(String guestClass) {
        if (closed) throw new IllegalStateException("GUEST_SERVICE_FRAMEWORK_BRIDGE_CLOSED");
        Record selected = null;
        synchronized (records) {
            for (Record candidate : records.values()) {
                if (!candidate.className.equals(guestClass)) continue;
                if (selected == null || candidate.lastStartId > selected.lastStartId) {
                    selected = candidate;
                }
            }
        }
        if (selected == null) {
            throw new IllegalStateException("FRAMEWORK_SERVICE_RECORD_MISSING:" + guestClass);
        }
        return selected;
    }

    private static Bundle frameworkResult(Record record, String status) {
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, status);
        out.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        out.putString(RuntimeKeys.COMPONENT_CLASS, record.className);
        // Keep the process-local framework record observable on every Broker-side probe.  The
        // framework callback is authoritative for these counters; returning them here also lets
        // a stale-start-id query cross the Guest/Broker boundary without depending on a second
        // manual Service record being present in the Guest component runtime.
        out.putInt(RuntimeKeys.SERVICE_START_COUNT, record.startCount);
        out.putInt(RuntimeKeys.SERVICE_LAST_START_ID, record.lastStartId);
        out.putInt(RuntimeKeys.SERVICE_CONNECTION_COUNT,
                record.brokerConnectionIntents.size());
        return out;
    }

    private static String required(Bundle request, String key) {
        String value = request == null ? "" : request.getString(key, "");
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }
    private Intent createIntent(Object data) {
        return (Intent) field(data, "intent");
    }

    private Intent lifecycleIntent(int what, Object data) {
        if (what == CREATE_SERVICE) return createIntent(data);
        if (what == BIND_SERVICE || what == UNBIND_SERVICE) {
            return (Intent) field(data, "intent");
        }
        if (what == SERVICE_ARGS) return (Intent) field(data, "args");
        return null;
    }

    private Intent decodeGuestIntent(Intent host) {
        if (host != null) RuntimeIntentWireCodec.materializePayloadForBroker(host.getExtras());
        return RuntimeIntentWireCodec.decode(host == null ? null : host.getExtras());
    }

    private boolean isFrameworkRoute(Intent intent) {
        return intent != null && intent.getBooleanExtra("frameworkServiceRoute", false);
    }

    private IBinder tokenFor(int what, Object data) {
        if (what == STOP_SERVICE) return data instanceof IBinder ? (IBinder) data : null;
        if (what == BIND_SERVICE || what == UNBIND_SERVICE || what == SERVICE_ARGS) {
            Object value = field(data, "token");
            return value instanceof IBinder ? (IBinder) value : null;
        }
        return null;
    }

    private ServiceInfo projectServiceInfo(Object data, String guestClass) {
        Object original = field(data, "info");
        ServiceInfo info = original instanceof ServiceInfo
                ? new ServiceInfo((ServiceInfo) original) : new ServiceInfo();
        VirtualPackageMetadata.Component component = session.packageMetadata.component(
                guestClass, VirtualPackageMetadata.Type.SERVICE);
        if (component == null) {
            throw new IllegalArgumentException("SERVICE_NOT_DECLARED:" + guestClass);
        }
        info.name = guestClass;
        info.packageName = session.spec.packageName;
        info.processName = component.processName().isEmpty()
                ? session.spec.processName : component.processName();
        info.exported = component.exported();
        info.enabled = component.enabled();
        info.permission = component.permission();
        info.flags = component.isolated() ? ServiceInfo.FLAG_ISOLATED_PROCESS : 0;
        setOptional(info, "metaData", component.metaData());
        if (component.stopWithTask()) {
            info.flags |= optionalStaticInt(ServiceInfo.class, "FLAG_STOP_WITH_TASK");
        }
        setOptional(info, "foregroundServiceType", component.foregroundServiceType());
        android.util.Log.i("CS_FGS_PROJECTION", "SERVICE_INFO service=" + guestClass
                + " declaredType=" + component.foregroundServiceType()
                + " projectedType=" + optionalIntField(info, "foregroundServiceType"));
        setOptional(info, "directBootAware", component.directBootAware());
        info.applicationInfo = new ApplicationInfo(session.context.getApplicationInfo());
        return info;
    }

    static Object activityManager() throws Exception {
        Class<?> type = Class.forName("android.app.ActivityManager");
        Method method = type.getDeclaredMethod("getService");
        method.setAccessible(true);
        return method.invoke(null);
    }

    private void serviceDone(IBinder token, int type, int startId, int result) throws Exception {
        serviceDone(token, type, startId, result, null);
    }

    private void serviceDone(IBinder token, int type, int startId, int result,
                             Intent serviceIntent) throws Exception {
        if (Build.VERSION.SDK_INT >= 35) {
            invokeActivityManager("serviceDoneExecuting", token, type, startId, result, serviceIntent);
        } else {
            invokeActivityManager("serviceDoneExecuting", token, type, startId, result);
        }
    }

    private void publishService(IBinder token, Intent intent, IBinder binder) throws Exception {
        invokeActivityManager("publishService", token, intent, binder);
    }

    private void unbindFinished(IBinder token, Intent intent, boolean rebind) throws Exception {
        invokeActivityManager("unbindFinished", token, intent, rebind);
    }

    private void invokeActivityManager(String name, Object... args) throws Exception {
        Method target = null;
        for (Method candidate : activityManager.getClass().getMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterTypes().length == args.length) {
                target = candidate;
                break;
            }
        }
        if (target == null) throw new NoSuchMethodException(name);
        try { target.invoke(activityManager, args); }
        catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RemoteException) throw (RemoteException) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
    }

    private void logLifecycle(String name, Record record, String stage) {
        Bundle event = new Bundle();
        event.putString(RuntimeKeys.STATUS, stage);
        event.putBoolean("frameworkOwnedService", true);
        event.putString(RuntimeKeys.COMPONENT_CLASS, record.className);
        event.putString(RuntimeKeys.SESSION_ID, session.spec.sessionId);
        event.putLong(RuntimeKeys.GENERATION, session.spec.generation);
        event.putInt(RuntimeKeys.PROCESS_SLOT, session.spec.processSlot);
        RuntimeEventLog.event(name, event);
    }

    private static void detach(Service service) throws Exception {
        Method method = Service.class.getDeclaredMethod("detachAndCleanUp");
        method.setAccessible(true);
        method.invoke(service);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("SERVICE_FIELD_UNAVAILABLE:" + name, error);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setOptional(Object target, String name, Object value) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException ignored) {
            // API/OEM field shape differs; the declared manifest contract remains available
            // through the virtual PackageManager even when the local framework omits a field.
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional service metadata must not break an otherwise valid Service lifecycle.
        }
    }

    private static int optionalStaticInt(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private static int optionalIntField(Object target, String name) {
        if (target == null) return 0;
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private static boolean booleanField(Object target, String name) { return Boolean.TRUE.equals(field(target, name)); }
    private static int intField(Object target, String name) { return ((Number) field(target, name)).intValue(); }

    private static void mapPut(Object map, Object key, Object value) throws Exception {
        Method put = map.getClass().getMethod("put", Object.class, Object.class);
        put.setAccessible(true);
        put.invoke(map, key, value);
    }

    private static Object mapGet(Object map, Object key) {
        try {
            Method get = map.getClass().getMethod("get", Object.class);
            get.setAccessible(true);
            return get.invoke(map, key);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("SERVICE_MAP_GET_FAILED", error);
        }
    }

    private static void mapRemove(Object map, Object key) {
        try {
            Method remove = map.getClass().getMethod("remove", Object.class);
            remove.setAccessible(true);
            remove.invoke(map, key);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
        }
    }

    private static void copyString(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) target.putExtra(key, source.getString(key));
    }

    private static void copyBoolean(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) target.putExtra(key, source.getBoolean(key));
    }

    private static void copyInt(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) target.putExtra(key, source.getInt(key));
    }

    private static void copyLong(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) target.putExtra(key, source.getLong(key));
    }

    private static void copyBinder(Bundle source, Intent target, String key) {
        if (source == null || !source.containsKey(key)) return;
        Bundle extras = target.getExtras();
        if (extras == null) {
            extras = new Bundle();
            target.putExtras(extras);
        }
        extras.putBinder(key, source.getBinder(key));
    }

    private static void copyStringList(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) {
            ArrayList<String> values = source.getStringArrayList(key);
            if (values != null) target.putStringArrayListExtra(key, new ArrayList<>(values));
        }
    }

    private static Bundle routeBundle(Intent intent) {
        if (intent == null || intent.getExtras() == null) return new Bundle();
        return new Bundle(intent.getExtras());
    }

    private static Bundle lifecycleRoute(Record record, Intent intent) {
        Bundle route = new Bundle(record.routeRequest);
        route.putAll(routeBundle(intent));
        return route;
    }

    private static final class RecoveryWaiter {
        final String component;
        final CountDownLatch completed = new CountDownLatch(1);
        volatile Bundle result;
        volatile Throwable failure;

        RecoveryWaiter(String component) { this.component = component; }

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
                    throw new IllegalStateException("FRAMEWORK_SERVICE_RECOVERY_TIMEOUT:" + component);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("FRAMEWORK_SERVICE_RECOVERY_INTERRUPTED:" + component,
                        error);
            }
            if (failure != null) {
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(failure);
            }
            return result == null ? new Bundle() : new Bundle(result);
        }
    }

    private static final class Record {
        final IBinder token;
        final Service service;
        final String className;
        final ServiceInfo info;
        final ComponentName stub;
        final Bundle routeRequest;
        final FrameworkServiceBindingLedger bindings = new FrameworkServiceBindingLedger();
        final FrameworkServiceBindingLedger brokerBindings = new FrameworkServiceBindingLedger();
        final Map<String, Intent> brokerConnectionIntents = new HashMap<>();
        int lastStartId;
        int startCount;
        IBinder lastBinder;
        Record(IBinder token, Service service, String className, ServiceInfo info,
               ComponentName stub, Bundle routeRequest) {
            this.token = token;
            this.service = service;
            this.className = className;
            this.info = info;
            this.stub = stub;
            this.routeRequest = routeRequest == null ? new Bundle() : new Bundle(routeRequest);
        }
    }

    static final class StopWaiter {
        final CountDownLatch done = new CountDownLatch(1);
    }

}
