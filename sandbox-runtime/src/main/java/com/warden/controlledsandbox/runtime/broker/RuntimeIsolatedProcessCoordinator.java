package com.warden.controlledsandbox.runtime.broker;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;

import com.warden.controlledsandbox.contract.IIsolatedGuestProcess;
import com.warden.controlledsandbox.contract.IsolatedProcessRequest;
import com.warden.controlledsandbox.contract.IsolatedProcessResult;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.port.SessionMetricsRepository;
import com.warden.controlledsandbox.domain.port.TokenGenerator;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService0;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService1;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService2;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService3;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.status.ServiceMetricsSource;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Owns the dedicated Android isolated-Service process channel.
 *
 * <p>This coordinator intentionally does not model isolated Activity, Receiver or Provider
 * execution. It owns a separate four-slot registry, capability tokens, Binder connections,
 * Service recovery and cleanup so the central Broker remains a route authority rather than a
 * second process manager implementation.</p>
 */
final class RuntimeIsolatedProcessCoordinator implements AutoCloseable {
    static final int SLOT_COUNT = 4;

    @FunctionalInterface interface InputValidator {
        void validate(Bundle input) throws Exception;
    }

    @FunctionalInterface interface SpecFactory {
        Bundle create(Bundle input, GuestSession session) throws Exception;
    }

    @FunctionalInterface interface SessionBundleFactory {
        Bundle create(GuestSession session, String status);
    }

    private final Service host;
    private final BrokerStateStore brokerState;
    private final Clock clock;
    private final TokenGenerator tokenGenerator;
    private final InputValidator inputValidator;
    private final SpecFactory specFactory;
    private final Supplier<RuntimeSystemServiceCoordinator> systemServices;
    private final SessionBundleFactory sessionBundles;
    private final SessionRegistry sessions;
    private final RuntimeServiceCoordinator services;
    private final ConcurrentMap<Integer, IsolatedConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> capabilities = new ConcurrentHashMap<>();

    RuntimeIsolatedProcessCoordinator(Service host, BrokerStateStore brokerState, Clock clock,
            TokenGenerator tokenGenerator, InputValidator inputValidator, SpecFactory specFactory,
            Supplier<RuntimeSystemServiceCoordinator> systemServices,
            SessionBundleFactory sessionBundles) {
        if (host == null || brokerState == null || clock == null || tokenGenerator == null
                || inputValidator == null || specFactory == null || systemServices == null
                || sessionBundles == null) {
            throw new IllegalArgumentException("isolated process dependencies are required");
        }
        this.host = host;
        this.brokerState = brokerState;
        this.clock = clock;
        this.tokenGenerator = tokenGenerator;
        this.inputValidator = inputValidator;
        this.specFactory = specFactory;
        this.systemServices = systemServices;
        this.sessionBundles = sessionBundles;
        this.sessions = new SessionRegistry(SLOT_COUNT, tokenGenerator);
        this.services = new RuntimeServiceCoordinator(brokerState, this::invokeGuest, clock);
    }

    SessionMetricsRepository sessionMetrics() { return sessions; }
    ServiceMetricsSource serviceMetrics() { return services; }

    synchronized Bundle invoke(Bundle request, IsolatedProcessRoutePolicy.Match suppliedMatch)
            throws Exception {
        Bundle input = new Bundle(request);
        IsolatedProcessRoutePolicy.Match match = IsolatedProcessRoutePolicy.requireIsolatedService(input);
        if (!match.componentClass().equals(suppliedMatch.componentClass())) {
            throw new SecurityException("ISOLATED_ROUTE_CHANGED_DURING_VALIDATION");
        }
        inputValidator.validate(input);
        String packageName = required(input, RuntimeKeys.PACKAGE_NAME);
        int userId = input.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
        String revision = required(input, RuntimeKeys.PACKAGE_REVISION);
        String processName = match.processName();
        input.putString(RuntimeKeys.PROCESS_NAME, processName);
        input.putString(RuntimeKeys.COMPONENT_CLASS, match.componentClass());
        input.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
        stopMismatchedSessions(packageName, userId, revision);

        GuestSession session = sessions.allocate(packageName, userId, processName, revision, now());
        GuestSession staleRecovery = null;
        boolean prepareRequired = session.state() != SessionState.READY
                && session.state() != SessionState.ACTIVE;
        if (session.state() == SessionState.RECOVERING) {
            staleRecovery = session;
            removeCapabilities(session.sessionId());
            session = sessions.beginRecovery(packageName, userId, processName,
                    session.generation(), now());
        } else if (session.state() == SessionState.ALLOCATED) {
            session = sessions.transition(packageName, userId, processName,
                    session.generation(), SessionState.PREPARING, now(), "");
        } else if (prepareRequired && session.state() != SessionState.PREPARING) {
            throw new IllegalStateException("ISOLATED_SESSION_BUSY:" + session.state());
        }

        String key = processKey(packageName, userId, processName);
        Bundle spec = brokerState.prepared(key);
        String capability = capability(session, prepareRequired);
        if (prepareRequired) {
            spec = specFactory.create(input, session);
            spec.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
            spec.putString(RuntimeKeys.ISOLATED_CAPABILITY_TOKEN, capability);
            systemServices().attach(session, spec);
            final GuestSession preparingSession = session;
            final Bundle preparingSpec = new Bundle(spec);
            final String isolatedComponent = match.componentClass();
            try {
                IsolatedProcessResult prepared = call(preparingSession.processSlot(), worker ->
                        worker.prepare(request(preparingSession, isolatedComponent,
                                "PREPARE_ISOLATED_SERVICE", capability, preparingSpec)));
                Bundle preparedPayload = requireResult(preparingSession, isolatedComponent, prepared);
                if ("FAILED".equals(preparedPayload.getString(RuntimeKeys.STATUS, ""))) {
                    throw new IllegalStateException("ISOLATED_GUEST_PREPARE_FAILED:"
                            + preparedPayload.getString(RuntimeKeys.ERROR_MESSAGE, ""));
                }
                if (staleRecovery != null) services.recoverSession(staleRecovery, session, spec);
            } catch (Throwable error) {
                if (staleRecovery != null) services.invalidate(staleRecovery);
                systemServices().stop(session);
                sessions.transition(packageName, userId, processName, session.generation(),
                        SessionState.FAILED, now(), String.valueOf(error.getMessage()));
                removeCapabilities(session.sessionId());
                releaseConnection(session.processSlot());
                throw error;
            }
            session = sessions.transition(packageName, userId, processName,
                    session.generation(), SessionState.READY, now(), "");
            Bundle cached = new Bundle(spec);
            cached.putString(RuntimeKeys.ISOLATED_CAPABILITY_TOKEN, capability);
            brokerState.putPrepared(key, cached);
        } else {
            if (spec == null) throw new IllegalStateException("ISOLATED_PREPARED_SPEC_MISSING");
            if (!revision.equals(spec.getString(RuntimeKeys.PACKAGE_REVISION, ""))) {
                throw new IllegalStateException("ISOLATED_PREPARED_SPEC_REVISION_MISMATCH");
            }
        }

        Bundle call = new Bundle(spec);
        call.putAll(request);
        call.putString(RuntimeKeys.PACKAGE_NAME, packageName);
        call.putInt(RuntimeKeys.VIRTUAL_USER_ID, userId);
        call.putString(RuntimeKeys.PROCESS_NAME, processName);
        call.putString(RuntimeKeys.COMPONENT_CLASS, match.componentClass());
        call.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        call.putLong(RuntimeKeys.GENERATION, session.generation());
        call.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        call.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
        call.putString(RuntimeKeys.ISOLATED_CAPABILITY_TOKEN, capability);

        final GuestSession invokingSession = session;
        final Bundle isolatedCall = new Bundle(call);
        final String operation = required(isolatedCall, ComponentOperations.OPERATION);
        final String component = match.componentClass();
        IsolatedProcessResult isolated = call(invokingSession.processSlot(), worker -> worker.invoke(
                request(invokingSession, component, operation, capability, isolatedCall)));
        Bundle result = requireResult(invokingSession, component, isolated);
        result.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        result.putLong(RuntimeKeys.GENERATION, session.generation());
        result.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        result.putString(RuntimeKeys.PROCESS_NAME, session.processName());
        result.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
        if (!"FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
            services.applySuccessfulOperation(session, request, result);
            if (session.state() == SessionState.READY) {
                sessions.transition(packageName, userId, processName, session.generation(),
                        SessionState.ACTIVE, now(), "");
            }
        }
        return result;
    }

    synchronized void stopGuest(String packageName, int userId) {
        for (GuestSession session : new ArrayList<>(sessions.getAll(packageName, userId))) {
            stopSession(session);
        }
    }

    int purgeExpiredForeground() { return services.purgeExpiredForeground(); }

    void addLegacyStatus(Bundle status) {
        status.putInt(RuntimeKeys.ISOLATED_SLOT_CAPACITY, sessions.capacity());
        status.putInt(RuntimeKeys.ISOLATED_SLOT_USED, sessions.used());
        status.putInt(RuntimeKeys.ISOLATED_SESSION_COUNT, sessions.count());
    }

    private void stopMismatchedSessions(String packageName, int userId, String revision) {
        for (GuestSession session : new ArrayList<>(sessions.getAll(packageName, userId))) {
            if (session.state() != SessionState.STOPPED && session.state() != SessionState.FAILED
                    && !session.packageRevision().equals(revision)) {
                stopSession(session);
            }
        }
    }

    private void stopSession(GuestSession original) {
        GuestSession session = original;
        try {
            if (session.state() != SessionState.STOPPING
                    && session.state() != SessionState.STOPPED
                    && session.state() != SessionState.FAILED) {
                session = sessions.transition(session.packageName(), session.virtualUserId(),
                        session.processName(), session.generation(), SessionState.STOPPING, now(), "");
                String capability = requireCapability(session);
                final GuestSession stopping = session;
                callVoid(session.processSlot(), worker ->
                        worker.shutdown(stopping.sessionId(), stopping.generation(), capability));
                sessions.transition(session.packageName(), session.virtualUserId(),
                        session.processName(), session.generation(), SessionState.STOPPED, now(), "");
            }
        } catch (Throwable error) {
            GuestSession current = sessions.get(original.packageName(), original.virtualUserId(),
                    original.processName());
            if (current != null && current.state().canTransitionTo(SessionState.FAILED)) {
                sessions.transition(current.packageName(), current.virtualUserId(), current.processName(),
                        current.generation(), SessionState.FAILED, now(), String.valueOf(error.getMessage()));
            }
        } finally {
            brokerState.removePrepared(processKey(original.packageName(), original.virtualUserId(),
                    original.processName()));
            services.stopSession(original);
            RuntimeSystemServiceCoordinator system = systemServices.get();
            if (system != null) system.stop(original);
            removeCapabilities(original.sessionId());
            releaseConnection(original.processSlot());
        }
    }

    private Bundle invokeGuest(int slot, Bundle request) throws Exception {
        GuestSession session = sessions.findByProcessSlot(slot);
        if (session == null) throw new IllegalStateException("ISOLATED_SESSION_NOT_FOUND_FOR_SLOT");
        String component = required(request, RuntimeKeys.COMPONENT_CLASS);
        String capability = requireCapability(session);
        IsolatedProcessResult result = call(slot, worker -> worker.invoke(
                request(session, component, required(request, ComponentOperations.OPERATION),
                        capability, request)));
        return requireResult(session, component, result);
    }

    private IsolatedProcessRequest request(GuestSession session, String component,
            String operation, String capability, Bundle payload) {
        return new IsolatedProcessRequest(RuntimeProtocol.CURRENT, session.sessionId(),
                session.generation(), session.processSlot(), session.virtualUserId(),
                session.packageName(), session.processName(), component, session.packageRevision(),
                operation, capability, payload);
    }

    private Bundle requireResult(GuestSession session, String component,
            IsolatedProcessResult result) {
        if (result == null) throw new IllegalStateException("ISOLATED_PROCESS_EMPTY_RESULT");
        if (!session.sessionId().equals(result.sessionId())
                || session.generation() != result.generation()
                || session.processSlot() != result.processSlot()
                || !session.processName().equals(result.processName())
                || !component.equals(result.componentClass())) {
            throw new SecurityException("ISOLATED_PROCESS_RESULT_IDENTITY_MISMATCH");
        }
        if (result.platformPid() <= 0) throw new SecurityException("ISOLATED_PROCESS_PID_INVALID");
        if (result.platformUid() <= 0) throw new SecurityException("ISOLATED_PROCESS_UID_INVALID");
        if (result.platformUid() == host.getApplicationInfo().uid) {
            throw new SecurityException("ISOLATED_PROCESS_UID_EQUALS_HOST_UID");
        }
        Bundle payload = result.payload();
        payload.putInt(RuntimeKeys.ISOLATED_PLATFORM_PID, result.platformPid());
        payload.putInt(RuntimeKeys.ISOLATED_PLATFORM_UID, result.platformUid());
        payload.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
        if (!result.successful()) {
            payload.putString(RuntimeKeys.STATUS, "FAILED");
            payload.putString(RuntimeKeys.ERROR_TYPE, result.errorType());
            payload.putString(RuntimeKeys.ERROR_MESSAGE, result.errorMessage());
        }
        return payload;
    }

    private String capability(GuestSession session, boolean rotate) {
        String key = capabilityKey(session);
        if (rotate) {
            String token = tokenGenerator.nextToken("isolated-capability");
            if (token == null || token.trim().isEmpty() || token.length() > 192) {
                throw new IllegalStateException("ISOLATED_CAPABILITY_TOKEN_INVALID");
            }
            if (capabilities.containsValue(token)) {
                throw new IllegalStateException("ISOLATED_CAPABILITY_TOKEN_COLLISION");
            }
            capabilities.put(key, token);
            return token;
        }
        return requireCapability(session);
    }

    private String requireCapability(GuestSession session) {
        String value = capabilities.get(capabilityKey(session));
        if (value == null || value.trim().isEmpty()) {
            throw new SecurityException("ISOLATED_CAPABILITY_NOT_FOUND");
        }
        return value;
    }

    private static String capabilityKey(GuestSession session) {
        return session.sessionId() + "@" + session.generation();
    }

    private void removeCapabilities(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        String prefix = sessionId + "@";
        capabilities.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private IsolatedProcessResult call(int slot, IsolatedCall call) throws Exception {
        IsolatedConnection connection = requireConnection(slot);
        try {
            return call.run(connection.requireWorker());
        } catch (Exception error) {
            if (!connection.isAlive()) handleDisconnect(slot, connection,
                    "BINDER_CALL_FAILED:" + error.getClass().getSimpleName());
            throw error;
        }
    }

    private void callVoid(int slot, IsolatedVoidCall call) throws Exception {
        IsolatedConnection connection = requireConnection(slot);
        try {
            call.run(connection.requireWorker());
        } catch (Exception error) {
            if (!connection.isAlive()) handleDisconnect(slot, connection,
                    "BINDER_CALL_FAILED:" + error.getClass().getSimpleName());
            throw error;
        }
    }

    private IsolatedConnection requireConnection(int slot) throws Exception {
        IsolatedConnection connection;
        synchronized (this) {
            connection = connections.get(slot);
            if (connection != null && connection.isAlive()) return connection;
            if (connection == null) {
                connection = new IsolatedConnection(slot);
                connections.put(slot, connection);
                Intent intent = new Intent(host, serviceClassFor(slot));
                if (!host.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    connections.remove(slot);
                    throw new IllegalStateException("ISOLATED_BIND_FAILED");
                }
            }
        }
        if (!connection.await(10, TimeUnit.SECONDS) || !connection.isAlive()) {
            handleDisconnect(slot, connection, "ISOLATED_BIND_TIMEOUT");
            throw new IllegalStateException("ISOLATED_BIND_TIMEOUT");
        }
        return connection;
    }

    private void releaseConnection(int slot) {
        IsolatedConnection connection;
        synchronized (this) { connection = connections.remove(slot); }
        if (connection == null) return;
        connection.closing = true;
        connection.unlinkDeath();
        try { host.unbindService(connection); } catch (Exception ignored) { }
    }

    private void handleDisconnect(int slot, IsolatedConnection source, String reason) {
        GuestSession affected = null;
        synchronized (this) {
            IsolatedConnection current = connections.get(slot);
            if (current != source) return;
            connections.remove(slot);
            if (!source.closing) {
                affected = sessions.markSlotDisconnected(slot, now(), reason);
                if (affected != null) {
                    services.disconnectSession(affected);
                    RuntimeEventLog.event("ISOLATED_PROCESS_DISCONNECTED",
                            sessionBundles.create(affected, affected.state().name()));
                }
            }
        }
        if (affected != null) {
            removeCapabilities(affected.sessionId());
            brokerState.removePrepared(processKey(affected.packageName(), affected.virtualUserId(),
                    affected.processName()));
            RuntimeSystemServiceCoordinator system = systemServices.get();
            if (system != null) system.stop(affected);
        }
        source.unlinkDeath();
        try { host.unbindService(source); } catch (Exception ignored) { }
    }

    private RuntimeSystemServiceCoordinator systemServices() {
        RuntimeSystemServiceCoordinator value = systemServices.get();
        if (value == null) throw new IllegalStateException("SYSTEM_SERVICE_COORDINATOR_NOT_INITIALIZED");
        return value;
    }

    @Override public void close() {
        for (GuestSession session : sessions.snapshot()) services.stopSession(session);
        Integer[] slots;
        synchronized (this) { slots = connections.keySet().toArray(new Integer[0]); }
        for (int slot : slots) releaseConnection(slot);
        capabilities.clear();
        services.close();
    }

    private static Class<?> serviceClassFor(int slot) {
        switch (slot) {
            case 0: return IsolatedGuestProcessService0.class;
            case 1: return IsolatedGuestProcessService1.class;
            case 2: return IsolatedGuestProcessService2.class;
            case 3: return IsolatedGuestProcessService3.class;
            default: throw new IllegalArgumentException("Invalid isolated process slot: " + slot);
        }
    }

    private static String processKey(String packageName, int userId, String processName) {
        return RuntimeBrokerService.ownerKey(packageName, userId) + ":" + processName;
    }

    private static String required(Bundle bundle, String key) {
        String value = bundle.getString(key, "");
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private long now() { return clock.nowMillis(); }

    private interface IsolatedCall {
        IsolatedProcessResult run(IIsolatedGuestProcess worker) throws Exception;
    }

    private interface IsolatedVoidCall {
        void run(IIsolatedGuestProcess worker) throws Exception;
    }

    private final class IsolatedConnection implements ServiceConnection, IBinder.DeathRecipient {
        final int slot;
        final CountDownLatch connected = new CountDownLatch(1);
        volatile IIsolatedGuestProcess worker;
        volatile IBinder binderToken;
        volatile boolean closing;

        IsolatedConnection(int slot) { this.slot = slot; }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binderToken = service;
            worker = IIsolatedGuestProcess.Stub.asInterface(service);
            try { service.linkToDeath(this, 0); }
            catch (Throwable error) { worker = null; binderToken = null; }
            finally { connected.countDown(); }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            worker = null;
            binderToken = null;
            connected.countDown();
            handleDisconnect(slot, this, "SERVICE_DISCONNECTED");
        }

        @Override public void onBindingDied(ComponentName name) {
            worker = null;
            binderToken = null;
            connected.countDown();
            handleDisconnect(slot, this, "BINDING_DIED");
        }

        @Override public void onNullBinding(ComponentName name) {
            worker = null;
            binderToken = null;
            connected.countDown();
            handleDisconnect(slot, this, "NULL_BINDING");
        }

        @Override public void binderDied() {
            worker = null;
            binderToken = null;
            handleDisconnect(slot, this, "BINDER_DIED");
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return connected.await(timeout, unit);
        }

        boolean isAlive() {
            IBinder token = binderToken;
            return worker != null && token != null && token.isBinderAlive();
        }

        IIsolatedGuestProcess requireWorker() {
            IIsolatedGuestProcess value = worker;
            if (value == null || !isAlive()) {
                throw new IllegalStateException("ISOLATED_BINDER_DEAD");
            }
            return value;
        }

        void unlinkDeath() {
            IBinder token = binderToken;
            if (token != null) {
                try { token.unlinkToDeath(this, 0); } catch (Throwable ignored) { }
            }
        }
    }
}
