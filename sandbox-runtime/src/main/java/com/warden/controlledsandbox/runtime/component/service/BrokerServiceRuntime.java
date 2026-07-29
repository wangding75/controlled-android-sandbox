package com.warden.controlledsandbox.runtime.component.service;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.app.Service;
import android.os.Bundle;
import com.warden.controlledsandbox.domain.component.service.ForegroundServiceStateMachine;
import com.warden.controlledsandbox.domain.component.service.ServiceRuntimeRegistry;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.session.GuestSession;
import java.util.List;

/** Broker-owned production state for started, bound, foreground, and recovering Guest services. */
public final class BrokerServiceRuntime {
    private final ServiceRuntimeRegistry registry = new ServiceRuntimeRegistry();
    private final Clock clock;

    public BrokerServiceRuntime() { this(System::currentTimeMillis); }

    public BrokerServiceRuntime(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("clock is required");
        this.clock = clock;
    }

    public synchronized ServiceRuntimeRegistry.Snapshot applySuccessfulOperation(
            GuestSession session, Bundle request, Bundle result) {
        if (request == null || result == null || "FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) return null;
        String operation = request.getString(ComponentOperations.OPERATION, "");
        String component = request.getString(RuntimeKeys.COMPONENT_CLASS, "");
        if (component.trim().isEmpty()) return null;
        ServiceRuntimeRegistry.Snapshot snapshot = null;
        String instance = instanceId(session);
        switch (operation) {
            case ComponentOperations.START_SERVICE -> snapshot = registry.start(
                    instance, component, session.processName(), restartMode(result), session.generation(),
                    request.getString(ComponentOperations.ACTION, ""), false);
            case ComponentOperations.START_FOREGROUND_SERVICE -> snapshot = registry.startForegroundRequested(
                    instance, component, session.processName(), restartMode(result), session.generation(),
                    request.getString(ComponentOperations.ACTION, ""), clock.nowMillis(),
                    request.getLong(RuntimeKeys.SERVICE_FOREGROUND_PROMOTION_TIMEOUT_MS,
                            ForegroundServiceStateMachine.DEFAULT_PROMOTION_TIMEOUT_MS),
                    request.getBoolean(RuntimeKeys.SERVICE_FOREGROUND_BACKGROUND_ALLOWED, true),
                    request.getString(RuntimeKeys.SERVICE_FOREGROUND_EXEMPTION_REASON, ""),
                    request.getInt(RuntimeKeys.SERVICE_FOREGROUND_DECLARED_TYPE_MASK, 0));
            case ComponentOperations.STOP_SERVICE -> {
                if (registry.find(instance, component) != null) {
                    snapshot = registry.stopStarted(instance, component, session.generation());
                }
            }
            case ComponentOperations.STOP_SERVICE_START_ID -> {
                if (registry.find(instance, component) != null) {
                    snapshot = registry.stopStartId(instance, component,
                            request.getInt(RuntimeKeys.SERVICE_START_ID, -1), session.generation());
                }
            }
            case ComponentOperations.SET_SERVICE_FOREGROUND -> {
                if (registry.find(instance, component) != null) {
                    boolean foreground = request.getBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, false);
                    snapshot = foreground
                            ? registry.promoteForeground(instance, component, session.generation(),
                                    clock.nowMillis(),
                                    request.getInt(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED_TYPE_MASK, 0),
                                    request.getInt(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_ID, -1),
                                    request.getString(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_TAG, ""))
                            : registry.demoteForeground(instance, component, session.generation(),
                                    request.getBoolean(RuntimeKeys.SERVICE_FOREGROUND_REMOVE_NOTIFICATION, true),
                                    "SERVICE_FOREGROUND_DEMOTED");
                }
            }
            case ComponentOperations.BIND_SERVICE -> snapshot = registry.bind(
                    instance, component, session.processName(), required(request, RuntimeKeys.CONNECTION_ID),
                    session.generation());
            case ComponentOperations.UNBIND_SERVICE -> {
                if (registry.find(instance, component) != null) {
                    snapshot = registry.unbind(instance, component,
                            required(request, RuntimeKeys.CONNECTION_ID), session.generation());
                }
            }
            default -> { return null; }
        }
        if (snapshot != null) addSnapshot(result, snapshot);
        result.putInt(RuntimeKeys.SERVICE_RECORD_COUNT, registry.snapshot().size());
        return snapshot;
    }

    public synchronized ServiceRuntimeRegistry.Snapshot connectionDied(
            GuestSession session, String component, String connectionId) {
        ServiceRuntimeRegistry.Snapshot existing = registry.find(instanceId(session), component);
        if (existing == null || existing.generation() != session.generation()) return existing;
        return registry.disconnect(instanceId(session), component, connectionId, session.generation());
    }

    public synchronized List<ServiceRuntimeRegistry.Snapshot> processDisconnected(GuestSession stale) {
        return registry.markProcessDied(instanceId(stale), stale.processName(), stale.generation());
    }

    public synchronized List<ServiceRuntimeRegistry.Snapshot> recovering(GuestSession stale) {
        return registry.recovering(instanceId(stale), stale.processName(), stale.generation());
    }

    public synchronized List<ServiceRuntimeRegistry.Snapshot> processRecovered(GuestSession stale, GuestSession current) {
        return registry.completeProcessRecovery(instanceId(stale), stale.processName(),
                stale.generation(), current.generation(), clock.nowMillis());
    }

    public synchronized List<ServiceRuntimeRegistry.Snapshot> expireForeground() {
        return registry.expireForeground(clock.nowMillis());
    }

    public synchronized int invalidate(GuestSession session) {
        return registry.destroyInstance(instanceId(session), session.generation());
    }

    public synchronized int recordCount() { return registry.snapshot().size(); }
    public synchronized List<ServiceRuntimeRegistry.Snapshot> snapshot() { return registry.snapshot(); }

    private static ServiceRuntimeRegistry.RestartMode restartMode(Bundle result) {
        int value = result.getInt("onStartCommandResult", Service.START_NOT_STICKY);
        if (value == Service.START_STICKY) return ServiceRuntimeRegistry.RestartMode.STICKY;
        if (value == Service.START_REDELIVER_INTENT) return ServiceRuntimeRegistry.RestartMode.REDELIVER_INTENT;
        return ServiceRuntimeRegistry.RestartMode.NOT_STICKY;
    }

    public static void addSnapshot(Bundle result, ServiceRuntimeRegistry.Snapshot snapshot) {
        ForegroundServiceStateMachine.Snapshot foreground = snapshot.foregroundSnapshot();
        result.putString(RuntimeKeys.SERVICE_STATE, snapshot.state().name());
        result.putString(RuntimeKeys.SERVICE_RESTART_MODE, snapshot.restartMode().name());
        result.putInt(RuntimeKeys.SERVICE_START_COUNT, snapshot.startCount());
        result.putInt(RuntimeKeys.SERVICE_CONNECTION_COUNT, snapshot.connectionIds().size());
        result.putInt(RuntimeKeys.SERVICE_LAST_START_ID, snapshot.lastStartId());
        result.putBoolean(RuntimeKeys.SERVICE_FOREGROUND, snapshot.foreground());
        result.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, snapshot.foregroundRequested());
        result.putString(RuntimeKeys.SERVICE_FOREGROUND_STATE, foreground.state().name());
        result.putLong(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED_AT_MS, foreground.requestedAtMs());
        result.putLong(RuntimeKeys.SERVICE_FOREGROUND_DEADLINE_MS, foreground.promotionDeadlineMs());
        result.putLong(RuntimeKeys.SERVICE_FOREGROUND_PROMOTED_AT_MS, foreground.promotedAtMs());
        result.putInt(RuntimeKeys.SERVICE_FOREGROUND_DECLARED_TYPE_MASK, foreground.declaredTypeMask());
        result.putInt(RuntimeKeys.SERVICE_FOREGROUND_ACTIVE_TYPE_MASK, foreground.activeTypeMask());
        result.putInt(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_ID, foreground.notificationId());
        result.putString(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_TAG, foreground.notificationTag());
        result.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_BACKGROUND_ALLOWED,
                foreground.backgroundStartAllowed());
        result.putString(RuntimeKeys.SERVICE_FOREGROUND_EXEMPTION_REASON, foreground.exemptionReason());
        result.putString(RuntimeKeys.SERVICE_FOREGROUND_TERMINAL_REASON, foreground.terminalReason());
    }

    public static String instanceId(GuestSession session) {
        return "u" + session.virtualUserId() + ":" + session.packageName();
    }

    private static String required(Bundle value, String key) {
        String result = value.getString(key, "");
        if (result.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return result;
    }
}
