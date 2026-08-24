package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import com.warden.controlledsandbox.domain.component.service.ServiceRuntimeRegistry;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.runtime.component.service.BrokerServiceRuntime;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.status.ServiceMetricsSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Broker-owned Service authority.
 *
 * <p>Owns started/bound/foreground state, client Binder-death cleanup, and sticky/redelivery
 * recovery. RuntimeBrokerService only supplies Guest process allocation and Binder invocation.</p>
 */
public final class RuntimeServiceCoordinator implements ServiceMetricsSource {
    @FunctionalInterface public interface GuestInvoker {
        Bundle invoke(int processSlot, Bundle request) throws Exception;
    }

    private final BrokerStateStore brokerState;
    private final GuestInvoker guestInvoker;
    private final Clock clock;
    private final BrokerServiceRuntime runtime;
    private final Map<String, ConnectionLease> connections = new LinkedHashMap<>();

    public RuntimeServiceCoordinator(BrokerStateStore brokerState, GuestInvoker guestInvoker) {
        this(brokerState, guestInvoker, System::currentTimeMillis);
    }

    public RuntimeServiceCoordinator(BrokerStateStore brokerState, GuestInvoker guestInvoker, Clock clock) {
        if (brokerState == null || guestInvoker == null || clock == null) {
            throw new IllegalArgumentException("Service coordinator dependencies are required");
        }
        this.brokerState = brokerState;
        this.guestInvoker = guestInvoker;
        this.clock = clock;
        this.runtime = new BrokerServiceRuntime(clock);
    }

    public ServiceRuntimeRegistry.Snapshot applySuccessfulOperation(
            GuestSession session, Bundle request, Bundle result) {
        ServiceRuntimeRegistry.Snapshot snapshot = runtime.applySuccessfulOperation(session, request, result);
        String operation = request == null ? "" : request.getString(ComponentOperations.OPERATION, "");
        if (ComponentOperations.BIND_SERVICE.equals(operation) && snapshot != null) {
            registerConnection(session, request, result);
        } else if (ComponentOperations.UNBIND_SERVICE.equals(operation)) {
            removeConnection(session, request.getString(RuntimeKeys.COMPONENT_CLASS, ""),
                    request.getString(RuntimeKeys.CONNECTION_ID, ""));
        }
        return snapshot;
    }

    /** Opens a framework-owned start transaction before Guest onStartCommand executes. */
    public ServiceRuntimeRegistry.Snapshot beginFrameworkStart(
            GuestSession session, Bundle request, Bundle result) {
        return runtime.beginFrameworkStart(session, request, result);
    }

    /** Commits a framework-owned start after Guest onStartCommand returns its restart mode. */
    public ServiceRuntimeRegistry.Snapshot completeFrameworkStart(
            GuestSession session, Bundle request, Bundle result) {
        return runtime.completeFrameworkStart(session, request, result);
    }

    public List<ServiceRuntimeRegistry.Snapshot> disconnectSession(GuestSession session) {
        removeSessionConnections(session);
        return runtime.processDisconnected(session);
    }

    /** Recreates sticky/redeliver services in the new Guest process before marking recovery complete. */
    public List<ServiceRuntimeRegistry.Snapshot> recoverSession(
            GuestSession stale, GuestSession current, Bundle currentSpec) throws Exception {
        removeSessionConnections(stale);
        List<ServiceRuntimeRegistry.Snapshot> recovering = runtime.recovering(stale);
        List<ServiceRuntimeRegistry.Snapshot> recovered = new ArrayList<>();
        Map<String, ServiceRuntimeRegistry.RestartMode> recoveryModes = new LinkedHashMap<>();
        for (ServiceRuntimeRegistry.Snapshot service : recovering) {
            Bundle call = new Bundle(currentSpec);
            call.putString(ComponentOperations.OPERATION, service.frameworkOwned()
                    ? ComponentOperations.RECOVER_FRAMEWORK_SERVICE
                    : service.recoverForeground()
                    ? ComponentOperations.START_FOREGROUND_SERVICE : ComponentOperations.START_SERVICE);
            call.putString(RuntimeKeys.COMPONENT_CLASS, service.component());
            call.putBoolean(RuntimeKeys.SERVICE_RECOVERY, true);
            call.putInt(RuntimeKeys.SERVICE_START_ID, service.lastStartId());
            boolean redeliver = service.restartMode() == ServiceRuntimeRegistry.RestartMode.REDELIVER_INTENT;
            call.putBoolean(RuntimeKeys.SERVICE_REDELIVERED, redeliver);
            // Android delivers a null Intent for START_STICKY and the exact last Intent for
            // START_REDELIVER_INTENT.  Keep the action-only field as a compatibility fallback for
            // records created before the Broker began retaining the full wire envelope.
            call.putString(ComponentOperations.ACTION, "");
            Bundle redeliveryIntent = redeliver ? runtime.recoveryIntent(service) : null;
            if (redeliveryIntent != null) call.putAll(redeliveryIntent);
            else if (redeliver) call.putString(ComponentOperations.ACTION, service.lastStartAction());
            byte[] redeliveryPayload = RuntimeIntentWireCodec.routePayload(call);
            if (redeliveryPayload != null) {
                RuntimeIntentWireCodec.attachRoutePayloadDescriptor(call, redeliveryPayload);
            }
            if (service.recoverForeground()) {
                call.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_BACKGROUND_ALLOWED, true);
                call.putString(RuntimeKeys.SERVICE_FOREGROUND_EXEMPTION_REASON, "PROCESS_RECOVERY");
                call.putInt(RuntimeKeys.SERVICE_FOREGROUND_DECLARED_TYPE_MASK,
                        service.foregroundSnapshot().declaredTypeMask());
            }
            Bundle result = guestInvoker.invoke(current.processSlot(), call);
            if (result == null || "FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
                String reason = result == null ? "NO_RESULT"
                        : result.getString(RuntimeKeys.ERROR_TYPE, result.getString(RuntimeKeys.STATUS, "FAILED"));
                throw new IllegalStateException("SERVICE_RECOVERY_FAILED:" + service.component() + ":" + reason);
            }
            if (service.frameworkOwned()) {
                recovered.add(runtime.completeFrameworkRecovery(stale, current, service, call, result));
            } else {
                recoveryModes.put(service.component(), BrokerServiceRuntime.restartModeOf(result));
            }
        }
        recovered.addAll(runtime.processRecovered(stale, current, recoveryModes));
        return java.util.Collections.unmodifiableList(recovered);
    }


    /** Expires services that failed to promote before their bounded foreground deadline. */
    public int purgeExpiredForeground() {
        List<ServiceRuntimeRegistry.Snapshot> expired = runtime.expireForeground();
        for (ServiceRuntimeRegistry.Snapshot service : expired) {
            Bundle base = brokerState.prepared(service.instanceId() + ":" + service.processName());
            if (base == null) continue;
            Bundle request = new Bundle(base);
            request.putString(ComponentOperations.OPERATION, ComponentOperations.STOP_SERVICE);
            request.putString(RuntimeKeys.COMPONENT_CLASS, service.component());
            request.putString(RuntimeKeys.SERVICE_FOREGROUND_TERMINAL_REASON,
                    "FOREGROUND_SERVICE_PROMOTION_TIMEOUT");
            int slot = request.getInt(RuntimeKeys.PROCESS_SLOT, -1);
            if (slot < 0) continue;
            try { guestInvoker.invoke(slot, request); }
            catch (Exception ignored) { }
        }
        return expired.size();
    }

    public int stopSession(GuestSession session) {
        removeSessionConnections(session);
        return runtime.invalidate(session);
    }

    public int invalidate(GuestSession session) { return stopSession(session); }
    @Override public int recordCount() { return runtime.recordCount(); }
    public List<ServiceRuntimeRegistry.Snapshot> snapshot() { return runtime.snapshot(); }

    public synchronized int activeConnectionLeases() { return connections.size(); }

    public void close() {
        List<ConnectionLease> leases;
        synchronized (this) {
            leases = new ArrayList<>(connections.values());
            connections.clear();
        }
        for (ConnectionLease lease : leases) lease.unlink();
    }

    private void registerConnection(GuestSession session, Bundle request, Bundle result) {
        String component = required(request, RuntimeKeys.COMPONENT_CLASS);
        String connectionId = required(request, RuntimeKeys.CONNECTION_ID);
        IBinder token = request.getBinder(RuntimeKeys.SERVICE_CONNECTION_BINDER);
        if (token == null) {
            result.putBoolean(RuntimeKeys.SERVICE_CONNECTION_DEATH_TRACKED, false);
            return;
        }
        String key = connectionKey(session, component, connectionId);
        ConnectionLease lease = new ConnectionLease(key, session, component, connectionId, token);
        synchronized (this) {
            if (connections.containsKey(key)) throw new IllegalStateException("DUPLICATE_SERVICE_CONNECTION_LEASE");
            connections.put(key, lease);
        }
        try {
            token.linkToDeath(lease, 0);
            lease.linked = true;
            synchronized (this) {
                if (connections.get(key) != lease) {
                    lease.unlink();
                    throw new IllegalStateException("SERVICE_CONNECTION_DIED_DURING_REGISTRATION");
                }
            }
            result.putBoolean(RuntimeKeys.SERVICE_CONNECTION_DEATH_TRACKED, true);
        } catch (RemoteException | RuntimeException error) {
            synchronized (this) { connections.remove(key, lease); }
            bestEffortGuestUnbind(lease);
            runtime.connectionDied(session, component, connectionId);
            throw new IllegalStateException("SERVICE_CONNECTION_TOKEN_DEAD", error);
        }
    }

    private void removeConnection(GuestSession session, String component, String connectionId) {
        if (component == null || component.trim().isEmpty() || connectionId == null || connectionId.trim().isEmpty()) return;
        ConnectionLease lease;
        synchronized (this) { lease = connections.remove(connectionKey(session, component, connectionId)); }
        if (lease != null) lease.unlink();
    }

    private void connectionDied(ConnectionLease lease) {
        lease.linked = false;
        synchronized (this) {
            if (!connections.remove(lease.key, lease)) return;
        }
        bestEffortGuestUnbind(lease);
        try { runtime.connectionDied(lease.session, lease.component, lease.connectionId); }
        catch (RuntimeException ignored) { }
    }

    private void bestEffortGuestUnbind(ConnectionLease lease) {
        Bundle base = brokerState.prepared(processKey(lease.session));
        if (base == null) return;
        Bundle request = new Bundle(base);
        request.putString(ComponentOperations.OPERATION, ComponentOperations.UNBIND_SERVICE);
        request.putString(RuntimeKeys.COMPONENT_CLASS, lease.component);
        request.putString(RuntimeKeys.CONNECTION_ID, lease.connectionId);
        try { guestInvoker.invoke(lease.session.processSlot(), request); }
        catch (Exception ignored) { }
    }

    private void removeSessionConnections(GuestSession session) {
        List<ConnectionLease> removed = new ArrayList<>();
        synchronized (this) {
            java.util.Iterator<Map.Entry<String, ConnectionLease>> iterator = connections.entrySet().iterator();
            while (iterator.hasNext()) {
                ConnectionLease lease = iterator.next().getValue();
                if (sameSession(lease.session, session)) {
                    iterator.remove();
                    removed.add(lease);
                }
            }
        }
        for (ConnectionLease lease : removed) lease.unlink();
    }

    private static boolean sameSession(GuestSession first, GuestSession second) {
        return first.sessionId().equals(second.sessionId()) && first.generation() == second.generation();
    }

    private static String connectionKey(GuestSession session, String component, String connectionId) {
        return session.sessionId() + "#" + session.generation() + "#" + component + "#" + connectionId;
    }

    private static String processKey(GuestSession session) {
        return "u" + session.virtualUserId() + ":" + session.packageName()
                + ":" + session.processName();
    }

    private static String required(Bundle request, String key) {
        String value = request == null ? "" : request.getString(key, "");
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private final class ConnectionLease implements IBinder.DeathRecipient {
        final String key;
        final GuestSession session;
        final String component;
        final String connectionId;
        final IBinder token;
        volatile boolean linked;

        ConnectionLease(String key, GuestSession session, String component, String connectionId, IBinder token) {
            this.key = key;
            this.session = session;
            this.component = component;
            this.connectionId = connectionId;
            this.token = token;
        }

        @Override public void binderDied() { connectionDied(this); }
        void unlink() {
            if (!linked) return;
            linked = false;
            try { token.unlinkToDeath(this, 0); } catch (RuntimeException ignored) { }
        }
    }
}
