package com.warden.controlledsandbox.runtime.component.service;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.app.Service;
import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.component.service.ServiceRuntimeRegistry;
import java.util.List;

/** Broker-owned production state for started and bound Guest services. */
public final class BrokerServiceRuntime {
    private final ServiceRuntimeRegistry registry = new ServiceRuntimeRegistry();

    public synchronized void applySuccessfulOperation(GuestSession session, Bundle request, Bundle result) {
        if (request == null || result == null || "FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) return;
        String operation = request.getString(ComponentOperations.OPERATION, "");
        String component = request.getString(RuntimeKeys.COMPONENT_CLASS, "");
        if (component.trim().isEmpty()) return;
        ServiceRuntimeRegistry.Snapshot snapshot = null;
        String instance = instanceId(session);
        switch (operation) {
            case ComponentOperations.START_SERVICE -> snapshot = registry.start(
                    instance, component, session.processName(), restartMode(result), session.generation());
            case ComponentOperations.STOP_SERVICE -> {
                if (registry.find(instance, component) != null) {
                    snapshot = registry.stopStarted(instance, component, session.generation());
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
            default -> { return; }
        }
        if (snapshot != null) addSnapshot(result, snapshot);
        result.putInt(RuntimeKeys.SERVICE_RECORD_COUNT, registry.snapshot().size());
    }

    public synchronized List<ServiceRuntimeRegistry.Snapshot> processDisconnected(GuestSession stale) {
        return registry.markProcessDied(instanceId(stale), stale.processName(), stale.generation());
    }

    public synchronized List<ServiceRuntimeRegistry.Snapshot> processRecovered(GuestSession stale, GuestSession current) {
        return registry.completeProcessRecovery(instanceId(stale), stale.processName(),
                stale.generation(), current.generation());
    }

    public synchronized int invalidate(GuestSession session) {
        return registry.destroyInstance(instanceId(session), session.generation());
    }

    public synchronized int recordCount() { return registry.snapshot().size(); }

    private static ServiceRuntimeRegistry.RestartMode restartMode(Bundle result) {
        int value = result.getInt("onStartCommandResult", Service.START_NOT_STICKY);
        if (value == Service.START_STICKY) return ServiceRuntimeRegistry.RestartMode.STICKY;
        if (value == 3) return ServiceRuntimeRegistry.RestartMode.REDELIVER_INTENT;
        return ServiceRuntimeRegistry.RestartMode.NOT_STICKY;
    }

    private static void addSnapshot(Bundle result, ServiceRuntimeRegistry.Snapshot snapshot) {
        result.putString(RuntimeKeys.SERVICE_STATE, snapshot.state().name());
        result.putString(RuntimeKeys.SERVICE_RESTART_MODE, snapshot.restartMode().name());
        result.putInt(RuntimeKeys.SERVICE_START_COUNT, snapshot.startCount());
        result.putInt(RuntimeKeys.SERVICE_CONNECTION_COUNT, snapshot.connectionIds().size());
        result.putInt(RuntimeKeys.SERVICE_LAST_START_ID, snapshot.lastStartId());
    }

    private static String instanceId(GuestSession session) {
        return "u" + session.virtualUserId() + ":" + session.packageName();
    }

    private static String required(Bundle value, String key) {
        String result = value.getString(key, "");
        if (result.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return result;
    }
}
