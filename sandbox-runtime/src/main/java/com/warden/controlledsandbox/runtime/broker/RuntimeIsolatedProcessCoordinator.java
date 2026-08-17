package com.warden.controlledsandbox.runtime.broker;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.warden.controlledsandbox.contract.IIsolatedGuestProcess;
import com.warden.controlledsandbox.contract.IsolatedProcessRequest;
import com.warden.controlledsandbox.contract.IsolatedProcessResult;
import com.warden.controlledsandbox.contract.ProcessSlotContract;
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
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService4;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService5;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService6;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService7;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService8;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService9;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService10;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService11;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService12;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService13;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService14;
import com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService15;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.status.ServiceMetricsSource;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns the dedicated Android isolated-Service process channel.
 *
 * <p>This coordinator intentionally does not model isolated Activity, Receiver or Provider
 * execution. It owns a separate 16-slot registry, capability tokens, Binder connections,
 * Service recovery and cleanup so the central Broker remains a route authority rather than a
 * second process manager implementation. The pool size is defined by
 * {@link ProcessSlotContract#ISOLATED_SLOT_COUNT}; worker declarations and status projection
 * must use the same contract.</p>
 */
final class RuntimeIsolatedProcessCoordinator implements AutoCloseable {
    static final int SLOT_COUNT = ProcessSlotContract.ISOLATED_SLOT_COUNT;
    private static final long SHUTDOWN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10L);

    private static final Class<?>[] ISOLATED_SERVICE_CLASSES = {
            IsolatedGuestProcessService0.class, IsolatedGuestProcessService1.class,
            IsolatedGuestProcessService2.class, IsolatedGuestProcessService3.class,
            IsolatedGuestProcessService4.class, IsolatedGuestProcessService5.class,
            IsolatedGuestProcessService6.class, IsolatedGuestProcessService7.class,
            IsolatedGuestProcessService8.class, IsolatedGuestProcessService9.class,
            IsolatedGuestProcessService10.class, IsolatedGuestProcessService11.class,
            IsolatedGuestProcessService12.class, IsolatedGuestProcessService13.class,
            IsolatedGuestProcessService14.class, IsolatedGuestProcessService15.class
    };

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
    private final Consumer<GuestSession> shareCleaner;
    private final SessionRegistry sessions;
    private final RuntimeServiceCoordinator services;
    private final ConcurrentMap<Integer, IsolatedConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> capabilities = new ConcurrentHashMap<>();

    RuntimeIsolatedProcessCoordinator(Service host, BrokerStateStore brokerState, Clock clock,
            TokenGenerator tokenGenerator, InputValidator inputValidator, SpecFactory specFactory,
            Supplier<RuntimeSystemServiceCoordinator> systemServices,
            SessionBundleFactory sessionBundles, Consumer<GuestSession> shareCleaner) {
        if (host == null || brokerState == null || clock == null || tokenGenerator == null
                || inputValidator == null || specFactory == null || systemServices == null
                || sessionBundles == null || shareCleaner == null) {
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
        this.shareCleaner = shareCleaner;
        this.sessions = new SessionRegistry(SLOT_COUNT, tokenGenerator);
        this.services = new RuntimeServiceCoordinator(brokerState, this::invokeGuest, clock);
    }

    SessionMetricsRepository sessionMetrics() { return sessions; }
    ServiceMetricsSource serviceMetrics() { return services; }

    /**
     * Resolves an isolated lease for capability transports which terminate in the main Broker
     * process.  The ordinary and isolated registries intentionally remain separate so slot
     * allocation and recovery cannot alias, therefore callers must not look up an isolated
     * Binder operation in RuntimeBrokerService's ordinary registry.
     */
    GuestSession findStorageSession(String sessionId, long generation) {
        if (sessionId == null || sessionId.trim().isEmpty() || generation < 1) return null;
        for (GuestSession session : sessions.snapshot()) {
            if (session.sessionId().equals(sessionId) && session.generation() == generation
                    && session.state() != SessionState.STOPPED
                    && session.state() != SessionState.FAILED) {
                return session;
            }
        }
        return null;
    }

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
        padIsolatedSlots(packageName, userId, revision, processName,
                input.getInt(RuntimeKeys.SLOT_PAD_COUNT, 0),
                input.getInt(RuntimeKeys.SLOT_TARGET, -1));

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
                try {
                    if (staleRecovery != null) services.invalidate(staleRecovery);
                    systemServices().stop(session);
                    shareCleaner.accept(session);
                    sessions.transition(packageName, userId, processName, session.generation(),
                            SessionState.FAILED, now(), String.valueOf(error.getMessage()));
                    removeCapabilities(session.sessionId());
                    releaseConnection(session.processSlot());
                } finally {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
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
        RuntimeException firstFailure = null;
        for (GuestSession session : new ArrayList<>(sessions.getAll(packageName, userId))) {
            try {
                stopSession(session);
            } catch (RuntimeException error) {
                if (firstFailure == null) firstFailure = error;
            }
        }
        if (firstFailure != null) throw firstFailure;
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
        Throwable stopFailure = null;
        try {
            if (session.state() != SessionState.STOPPING
                    && session.state() != SessionState.STOPPED
                    && session.state() != SessionState.FAILED) {
                session = sessions.transition(session.packageName(), session.virtualUserId(),
                        session.processName(), session.generation(), SessionState.STOPPING, now(), "");
                String capability = requireCapability(session);
                final GuestSession stopping = session;
                IsolatedConnection connection = requireConnection(session.processSlot());
                try {
                    connection.requireWorker().shutdown(
                            stopping.sessionId(), stopping.generation(), capability);
                } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                            .rethrowIfFatal(error);
                    // A process can die while the shutdown Binder transaction is unwinding.  In
                    // that case the death callback is the successful physical stop signal; do not
                    // turn an already-dead worker into a false lifecycle failure.
                    if (!connection.terminated()) {
                        if (error instanceof RuntimeException runtime) throw runtime;
                        if (error instanceof Error fatal) throw fatal;
                        throw new IllegalStateException("ISOLATED_SHUTDOWN_CALL_FAILED", error);
                    }
                }
                if (!connection.awaitTerminated(SHUTDOWN_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS)) {
                    abortConnection(connection, "ISOLATED_SHUTDOWN_PROCESS_TIMEOUT");
                    throw new IllegalStateException("ISOLATED_SHUTDOWN_PROCESS_TIMEOUT");
                }
                GuestSession current = sessions.get(session.packageName(), session.virtualUserId(),
                        session.processName());
                if (current != null && current.state() == SessionState.STOPPING) {
                    sessions.transition(session.packageName(), session.virtualUserId(),
                            session.processName(), session.generation(), SessionState.STOPPED,
                            now(), "");
                }
            }
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            stopFailure = error;
            GuestSession current = sessions.get(original.packageName(), original.virtualUserId(),
                    original.processName());
            if (current != null && current.state() != SessionState.FAILED
                    && current.state() != SessionState.STOPPED
                    && current.state().canTransitionTo(SessionState.FAILED)) {
                sessions.transition(current.packageName(), current.virtualUserId(), current.processName(),
                        current.generation(), SessionState.FAILED, now(), String.valueOf(error.getMessage()));
            }
        } finally {
            brokerState.removePrepared(processKey(original.packageName(), original.virtualUserId(),
                    original.processName()));
            services.stopSession(original);
            RuntimeSystemServiceCoordinator system = systemServices.get();
            if (system != null) system.stop(original);
            shareCleaner.accept(original);
            removeCapabilities(original.sessionId());
            releaseConnection(original.processSlot());
        }
        if (stopFailure != null) {
            if (stopFailure instanceof RuntimeException runtime) throw runtime;
            if (stopFailure instanceof Error fatal) throw fatal;
            throw new IllegalStateException("ISOLATED_GUEST_STOP_FAILED", stopFailure);
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
                boolean bound = host.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                Log.i("CS_ISOLATED_BIND", "bind slot=" + slot + " accepted=" + bound
                        + " connection=" + System.identityHashCode(connection));
                if (!bound) {
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
        Log.i("CS_ISOLATED_BIND", "unbind slot=" + slot + " connection="
                + System.identityHashCode(connection));
        connection.closing = true;
        connection.unlinkDeath();
        try { host.unbindService(connection); } catch (Exception ignored) { }
    }

    /**
     * Detaches a worker after the physical-stop deadline has expired.  The caller must keep the
     * lifecycle transaction failed; this method only prevents a timed-out Binder connection from
     * being reused and asks Android to tear down the concrete isolated service.
     */
    private void abortConnection(IsolatedConnection source, String reason) {
        synchronized (this) {
            if (!connections.remove(source.slot, source)) return;
            source.closing = true;
        }
        Log.e("CS_ISOLATED_BIND", "abort slot=" + source.slot + " reason=" + reason);
        source.unlinkDeath();
        try { host.unbindService(source); } catch (Exception ignored) { }
        host.stopService(new Intent(host, serviceClassFor(source.slot)));
    }

    private void handleDisconnect(int slot, IsolatedConnection source, String reason) {
        Log.w("CS_ISOLATED_BIND", "disconnect slot=" + slot + " reason=" + reason
                + " connection=" + System.identityHashCode(source));
        // Signal the physical death before entering the coordinator monitor.  Destructive
        // lifecycle calls may be waiting while holding the operation monitor; waiting for the
        // monitor first would deadlock the death barrier with this callback.
        source.terminated.countDown();
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
            shareCleaner.accept(affected);
        }
        source.unlinkDeath();
        try { host.unbindService(source); } catch (Exception ignored) { }
    }

    private RuntimeSystemServiceCoordinator systemServices() {
        RuntimeSystemServiceCoordinator value = systemServices.get();
        if (value == null) throw new IllegalStateException("SYSTEM_SERVICE_COORDINATOR_NOT_INITIALIZED");
        return value;
    }

    private void padIsolatedSlots(String packageName, int userId, String revision,
                                  String requestedProcess, int padCount, int slotTarget) {
        if (slotTarget >= 0) {
            if (!ProcessSlotContract.isIsolatedSlot(slotTarget)) {
                throw new IllegalArgumentException("ISOLATED_SLOT_TARGET_OUT_OF_RANGE:" + slotTarget);
            }
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                if (slot == slotTarget) continue;
                String padProcess = packageName + ":__iso_slot_pad_" + slot;
                if (padProcess.equals(requestedProcess)) {
                    throw new IllegalArgumentException("ISOLATED_SLOT_PAD_COLLIDES_WITH_REQUEST");
                }
                sessions.allocateExact(packageName, userId, padProcess, revision, slot, now());
            }
            return;
        }
        if (padCount <= 0) return;
        if (padCount > SLOT_COUNT) {
            throw new IllegalArgumentException("ISOLATED_SLOT_PAD_COUNT_OUT_OF_RANGE:" + padCount);
        }
        for (int index = 0; index < padCount; index++) {
            String padProcess = packageName + ":__iso_slot_pad_" + index;
            if (padProcess.equals(requestedProcess)) {
                throw new IllegalArgumentException("ISOLATED_SLOT_PAD_COLLIDES_WITH_REQUEST");
            }
            sessions.allocateExact(packageName, userId, padProcess, revision, index, now());
        }
    }

    @Override public void close() {
        for (GuestSession session : sessions.snapshot()) {
            services.stopSession(session);
            shareCleaner.accept(session);
        }
        Integer[] slots;
        synchronized (this) { slots = connections.keySet().toArray(new Integer[0]); }
        for (int slot : slots) releaseConnection(slot);
        capabilities.clear();
        services.close();
    }

    private static Class<?> serviceClassFor(int slot) {
        if (!ProcessSlotContract.isIsolatedSlot(slot)) {
            throw new IllegalArgumentException("Invalid isolated process slot: " + slot);
        }
        return ISOLATED_SERVICE_CLASSES[slot];
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
        final CountDownLatch terminated = new CountDownLatch(1);
        volatile IIsolatedGuestProcess worker;
        volatile IBinder binderToken;
        volatile boolean closing;

        IsolatedConnection(int slot) { this.slot = slot; }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i("CS_ISOLATED_BIND", "connected slot=" + slot + " component=" + name
                    + " connection=" + System.identityHashCode(this));
            binderToken = service;
            worker = IIsolatedGuestProcess.Stub.asInterface(service);
            try { service.linkToDeath(this, 0); }
            catch (Throwable error) {
                worker = null;
                binderToken = null;
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            finally { connected.countDown(); }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            Log.w("CS_ISOLATED_BIND", "serviceDisconnected slot=" + slot + " component=" + name);
            worker = null;
            binderToken = null;
            terminated.countDown();
            connected.countDown();
            handleDisconnect(slot, this, "SERVICE_DISCONNECTED");
        }

        @Override public void onBindingDied(ComponentName name) {
            Log.w("CS_ISOLATED_BIND", "bindingDied slot=" + slot + " component=" + name);
            worker = null;
            binderToken = null;
            terminated.countDown();
            connected.countDown();
            handleDisconnect(slot, this, "BINDING_DIED");
        }

        @Override public void onNullBinding(ComponentName name) {
            Log.w("CS_ISOLATED_BIND", "nullBinding slot=" + slot + " component=" + name);
            worker = null;
            binderToken = null;
            terminated.countDown();
            connected.countDown();
            handleDisconnect(slot, this, "NULL_BINDING");
        }

        @Override public void binderDied() {
            Log.w("CS_ISOLATED_BIND", "binderDied slot=" + slot);
            worker = null;
            binderToken = null;
            terminated.countDown();
            handleDisconnect(slot, this, "BINDER_DIED");
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return connected.await(timeout, unit);
        }

        boolean isAlive() {
            IBinder token = binderToken;
            return worker != null && token != null && token.isBinderAlive();
        }

        boolean terminated() { return terminated.getCount() == 0L; }

        boolean awaitTerminated(long timeout, TimeUnit unit) throws InterruptedException {
            return terminated.await(timeout, unit);
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
                try { token.unlinkToDeath(this, 0); } catch (Throwable ignored) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored); }
            }
        }
    }
}
