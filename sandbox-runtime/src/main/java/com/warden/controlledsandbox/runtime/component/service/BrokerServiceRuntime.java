package com.warden.controlledsandbox.runtime.component.service;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.app.Service;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.domain.component.service.ForegroundServiceStateMachine;
import com.warden.controlledsandbox.domain.component.service.ServiceRuntimeRegistry;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.session.GuestSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Broker-owned production state for started, bound, foreground, and recovering Guest services. */
public final class BrokerServiceRuntime {
    private final ServiceRuntimeRegistry registry = new ServiceRuntimeRegistry();
    private final Clock clock;
    /**
     * The domain registry deliberately remains Android-free.  Keep the platform Intent envelope
     * beside it in the Broker, where it can survive a Guest process death without leaking Guest
     * objects into the domain layer.  The map is bounded by the registry record limit.
     */
    private final Map<String, Bundle> lastStartIntents = new LinkedHashMap<>();

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
        boolean frameworkOwned = request.getBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, false);
        ServiceRuntimeRegistry.Snapshot snapshot = null;
        String instance = instanceId(session);
        switch (operation) {
            case ComponentOperations.START_SERVICE -> snapshot = registry.start(
                    instance, component, session.processName(), restartModeOf(result), session.generation(),
                    request.getString(ComponentOperations.ACTION, ""), false, frameworkOwned);
            case ComponentOperations.START_FOREGROUND_SERVICE -> snapshot = registry.startForegroundRequested(
                    instance, component, session.processName(), restartModeOf(result), session.generation(),
                    request.getString(ComponentOperations.ACTION, ""), clock.nowMillis(),
                    request.getLong(RuntimeKeys.SERVICE_FOREGROUND_PROMOTION_TIMEOUT_MS,
                            ForegroundServiceStateMachine.DEFAULT_PROMOTION_TIMEOUT_MS),
                    request.getBoolean(RuntimeKeys.SERVICE_FOREGROUND_BACKGROUND_ALLOWED, true),
                    request.getString(RuntimeKeys.SERVICE_FOREGROUND_EXEMPTION_REASON, ""),
                    effectiveDeclaredForegroundTypeMask(request, component),
                    frameworkOwned);
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
                    session.generation(), frameworkOwned);
            case ComponentOperations.UNBIND_SERVICE -> {
                if (registry.find(instance, component) != null) {
                    snapshot = registry.unbind(instance, component,
                            required(request, RuntimeKeys.CONNECTION_ID), session.generation());
                }
            }
            default -> { return null; }
        }
        if (snapshot != null) {
            if (ComponentOperations.START_SERVICE.equals(operation)
                    || ComponentOperations.START_FOREGROUND_SERVICE.equals(operation)) {
                rememberStartIntent(instance, component, request);
            } else if (snapshot.state() == ServiceRuntimeRegistry.State.DESTROYED) {
                forgetStartIntent(instance, component);
            }
            addSnapshot(result, snapshot);
        }
        result.putInt(RuntimeKeys.SERVICE_RECORD_COUNT, registry.snapshot().size());
        return snapshot;
    }

    public synchronized ServiceRuntimeRegistry.Snapshot connectionDied(
            GuestSession session, String component, String connectionId) {
        ServiceRuntimeRegistry.Snapshot existing = registry.find(instanceId(session), component);
        if (existing == null || existing.generation() != session.generation()) return existing;
        return registry.disconnect(instanceId(session), component, connectionId, session.generation());
    }

    /** Opens the framework Service start record before ActivityThread enters onStartCommand. */
    public synchronized ServiceRuntimeRegistry.Snapshot beginFrameworkStart(
            GuestSession session, Bundle request, Bundle result) {
        String component = required(request, RuntimeKeys.COMPONENT_CLASS);
        boolean foreground = request.getBoolean(RuntimeKeys.FRAMEWORK_SERVICE_FOREGROUND, false);
        ServiceRuntimeRegistry.Snapshot snapshot = registry.beginFrameworkStart(
                instanceId(session), component, session.processName(), session.generation(),
                request.getInt(RuntimeKeys.SERVICE_START_ID, -1),
                request.getString(ComponentOperations.ACTION, ""), foreground,
                clock.nowMillis(), request.getLong(RuntimeKeys.SERVICE_FOREGROUND_PROMOTION_TIMEOUT_MS,
                        ForegroundServiceStateMachine.DEFAULT_PROMOTION_TIMEOUT_MS),
                request.getBoolean(RuntimeKeys.SERVICE_FOREGROUND_BACKGROUND_ALLOWED, true),
                request.getString(RuntimeKeys.SERVICE_FOREGROUND_EXEMPTION_REASON, ""),
                effectiveDeclaredForegroundTypeMask(request, component));
        rememberStartIntent(instanceId(session), component, request);
        addSnapshot(result, snapshot);
        return snapshot;
    }

    /** Commits the final onStartCommand restart mode after a framework Service callback returns. */
    public synchronized ServiceRuntimeRegistry.Snapshot completeFrameworkStart(
            GuestSession session, Bundle request, Bundle result) {
        String component = required(request, RuntimeKeys.COMPONENT_CLASS);
        ServiceRuntimeRegistry.Snapshot snapshot = registry.completeFrameworkStart(
                instanceId(session), component, session.generation(),
                request.getInt(RuntimeKeys.SERVICE_START_ID, -1),
                restartModeOf(result), request.getString(ComponentOperations.ACTION, ""));
        addSnapshot(result, snapshot);
        return snapshot;
    }

    public synchronized List<ServiceRuntimeRegistry.Snapshot> processDisconnected(GuestSession stale) {
        String instance = instanceId(stale);
        List<ServiceRuntimeRegistry.Snapshot> affected = registry.markProcessDied(
                instance, stale.processName(), stale.generation());
        for (ServiceRuntimeRegistry.Snapshot service : affected) {
            if (service.state() == ServiceRuntimeRegistry.State.DESTROYED) {
                forgetStartIntent(instance, service.component());
            }
        }
        return affected;
    }

    public synchronized List<ServiceRuntimeRegistry.Snapshot> recovering(GuestSession stale) {
        return registry.recovering(instanceId(stale), stale.processName(), stale.generation());
    }

    public synchronized List<ServiceRuntimeRegistry.Snapshot> processRecovered(GuestSession stale, GuestSession current) {
        return processRecovered(stale, current, java.util.Collections.emptyMap());
    }

    public synchronized List<ServiceRuntimeRegistry.Snapshot> processRecovered(
            GuestSession stale, GuestSession current,
            Map<String, ServiceRuntimeRegistry.RestartMode> recoveryModes) {
        return registry.completeProcessRecovery(instanceId(stale), stale.processName(),
                stale.generation(), current.generation(), clock.nowMillis(), recoveryModes);
    }

    public synchronized ServiceRuntimeRegistry.Snapshot completeFrameworkRecovery(
            GuestSession stale, GuestSession current, ServiceRuntimeRegistry.Snapshot service,
            Bundle request, Bundle result) {
        int startId = result == null ? -1 : result.getInt(RuntimeKeys.SERVICE_START_ID,
                request == null ? -1 : request.getInt(RuntimeKeys.SERVICE_START_ID, -1));
        String action = request == null ? "" : request.getString(ComponentOperations.ACTION, "");
        ServiceRuntimeRegistry.Snapshot snapshot = registry.completeFrameworkRecovery(
                instanceId(stale), service.component(), stale.generation(), current.generation(),
                startId, action, clock.nowMillis(), result != null
                        && result.containsKey("onStartCommandResult")
                        ? restartModeOf(result) : null);
        if (result != null && result.getBoolean(RuntimeKeys.SERVICE_FOREGROUND_OBSERVED, false)) {
            snapshot = registry.promoteFrameworkRecovery(
                    instanceId(current), service.component(), current.generation(), clock.nowMillis(),
                    result.getInt(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED_TYPE_MASK, 0),
                    result.getInt(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_ID, -1),
                    result.getString(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_TAG, ""));
        }
        if (result != null) addSnapshot(result, snapshot);
        return snapshot;
    }

    public synchronized List<ServiceRuntimeRegistry.Snapshot> expireForeground() {
        return registry.expireForeground(clock.nowMillis());
    }

    public synchronized int invalidate(GuestSession session) {
        String instance = instanceId(session);
        int removed = registry.destroyInstance(instance, session.generation());
        forgetStartIntents(instance);
        return removed;
    }

    public synchronized int recordCount() { return registry.snapshot().size(); }
    public synchronized List<ServiceRuntimeRegistry.Snapshot> snapshot() { return registry.snapshot(); }

    public synchronized ServiceRuntimeRegistry.Snapshot find(GuestSession session, String component) {
        return registry.find(instanceId(session), component);
    }

    /** Returns a defensive copy of the last full wire Intent for START_REDELIVER_INTENT. */
    public synchronized Bundle recoveryIntent(ServiceRuntimeRegistry.Snapshot service) {
        if (service == null) return null;
        Bundle envelope = lastStartIntents.get(serviceKey(service.instanceId(), service.component()));
        return envelope == null ? null : new Bundle(envelope);
    }

    private void rememberStartIntent(String instance, String component, Bundle request) {
        Bundle envelope = new Bundle();
        if (request != null) {
            copyString(request, envelope, ComponentOperations.ACTION);
            copyString(request, envelope, RuntimeKeys.ACTIVITY_ACTION);
            copyString(request, envelope, RuntimeKeys.URI);
            copyString(request, envelope, RuntimeKeys.BROADCAST_SCHEME);
            copyString(request, envelope, RuntimeKeys.BROADCAST_HOST);
            if (request.containsKey(RuntimeKeys.BROADCAST_PORT)) {
                envelope.putInt(RuntimeKeys.BROADCAST_PORT,
                        request.getInt(RuntimeKeys.BROADCAST_PORT, -1));
            }
            copyString(request, envelope, RuntimeKeys.BROADCAST_PATH);
            copyString(request, envelope, RuntimeKeys.BROADCAST_MIME_TYPE);
            copyString(request, envelope, RuntimeKeys.TARGET_PACKAGE_NAME);
            copyString(request, envelope, RuntimeKeys.INTENT_COMPONENT_PACKAGE);
            copyString(request, envelope, RuntimeKeys.INTENT_COMPONENT_CLASS);
            if (request.containsKey(RuntimeKeys.ACTIVITY_FLAGS)) {
                envelope.putInt(RuntimeKeys.ACTIVITY_FLAGS,
                        request.getInt(RuntimeKeys.ACTIVITY_FLAGS, 0));
            }
            List<String> categories = request.getStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES);
            if (categories != null) {
                envelope.putStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES,
                        new ArrayList<>(categories));
            }
            byte[] wirePayload = request.getByteArray(RuntimeKeys.INTENT_WIRE_PAYLOAD);
            if (wirePayload != null && wirePayload.length != 0) {
                envelope.putByteArray(RuntimeKeys.INTENT_WIRE_PAYLOAD, wirePayload.clone());
            } else {
                Bundle extras = request.getBundle(RuntimeKeys.INTENT_EXTRAS);
                if (extras != null) envelope.putBundle(RuntimeKeys.INTENT_EXTRAS, new Bundle(extras));
            }
        }
        lastStartIntents.put(serviceKey(instance, component), envelope);
        while (lastStartIntents.size() > ServiceRuntimeRegistry.MAX_SERVICE_RECORDS) {
            lastStartIntents.remove(lastStartIntents.keySet().iterator().next());
        }
    }

    private void forgetStartIntent(String instance, String component) {
        lastStartIntents.remove(serviceKey(instance, component));
    }

    private void forgetStartIntents(String instance) {
        java.util.Iterator<String> iterator = lastStartIntents.keySet().iterator();
        String prefix = instance + "#";
        while (iterator.hasNext()) {
            if (iterator.next().startsWith(prefix)) iterator.remove();
        }
    }

    private static void copyString(Bundle source, Bundle target, String key) {
        if (!source.containsKey(key)) return;
        String value = source.getString(key);
        if (value != null) target.putString(key, value);
    }

    private static String serviceKey(String instance, String component) {
        return instance + "#" + component;
    }

    public static ServiceRuntimeRegistry.RestartMode restartModeOf(Bundle result) {
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
        result.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, snapshot.frameworkOwned());
    }

    /** Resolve an omitted foreground type from the authoritative virtual manifest projection. */
    private static int effectiveDeclaredForegroundTypeMask(Bundle request, String component) {
        if (request == null) return 0;
        int declared = request.getInt(RuntimeKeys.SERVICE_FOREGROUND_DECLARED_TYPE_MASK, 0);
        if (declared != 0) return declared;
        request.setClassLoader(VirtualPackageStateSnapshot.class.getClassLoader());
        VirtualPackageStateSnapshot state = request.getParcelable(RuntimeKeys.PACKAGE_STATE);
        if (state == null || component == null || component.trim().isEmpty()) return declared;
        for (VirtualComponentSnapshot candidate : state.components()) {
            if ("SERVICE".equals(candidate.type()) && component.equals(candidate.className())) {
                return candidate.foregroundServiceType();
            }
        }
        return declared;
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
