package com.warden.controlledsandbox.runtime.component.service;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.app.Service;
import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.component.service.ServiceRuntimeRegistry;
import java.util.List;

/** Broker-owned production state for started, bound, foreground, and recovering Guest services. */
public final class BrokerServiceRuntime {
    private final ServiceRuntimeRegistry registry = new ServiceRuntimeRegistry();

    public synchronized ServiceRuntimeRegistry.Snapshot applySuccessfulOperation(
            GuestSession session, Bundle request, Bundle result) {
        if (request == null || result == null || "FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) return null;
        String operation = request.getString(ComponentOperations.OPERATION, "");
        String component = request.getString(RuntimeKeys.COMPONENT_CLASS, "");
        if (component.trim().isEmpty()) return null;
        ServiceRuntimeRegistry.Snapshot snapshot = null;
        String instance = instanceId(session);
        switch (operation) {
            case ComponentOperations.START_SERVICE, ComponentOperations.START_FOREGROUND_SERVICE -> snapshot = registry.start(
                    instance, component, session.processName(), restartMode(result), session.generation(),
                    request.getString(ComponentOperations.ACTION, ""),
                    ComponentOperations.START_FOREGROUND_SERVICE.equals(operation)
                            || request.getBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, false));
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
                    snapshot = registry.setForeground(instance, component,
                            request.getBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, false), session.generation());
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
                stale.generation(), current.generation());
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
        result.putString(RuntimeKeys.SERVICE_STATE, snapshot.state().name());
        result.putString(RuntimeKeys.SERVICE_RESTART_MODE, snapshot.restartMode().name());
        result.putInt(RuntimeKeys.SERVICE_START_COUNT, snapshot.startCount());
        result.putInt(RuntimeKeys.SERVICE_CONNECTION_COUNT, snapshot.connectionIds().size());
        result.putInt(RuntimeKeys.SERVICE_LAST_START_ID, snapshot.lastStartId());
        result.putBoolean(RuntimeKeys.SERVICE_FOREGROUND, snapshot.foreground());
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
