package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Package-Service-owned durable peripheral/external-service profiles. */
final class VirtualPeripheralServicesStore {
    private final VirtualPeripheralServicesStorePersistence persistence;
    private final Map<VirtualSystemServiceStore.Scope, VirtualPeripheralServicesProfileSnapshot> profiles =
            new LinkedHashMap<>();
    private String maintenanceWarning = "";

    VirtualPeripheralServicesStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        persistence = new VirtualPeripheralServicesStorePersistence(
                new File(new File(filesDir, "package-service"),
                        "virtual-peripheral-services-v1.json"));
        load();
    }

    synchronized VirtualPeripheralServicesProfileSnapshot getOrCreate(
            VirtualSystemServiceStore.Scope scope) {
        VirtualPeripheralServicesProfileSnapshot value = profiles.get(scope);
        if (value != null) return value;
        value = VirtualPeripheralServicesDefaults.create(
                scope.packageName(), scope.virtualUserId(), 1L, System.currentTimeMillis());
        profiles.put(scope, value);
        persistOrRestore(scope, null);
        return value;
    }

    synchronized VirtualPeripheralServicesProfileSnapshot update(
            VirtualSystemServiceStore.Scope scope,
            VirtualPeripheralServicesProfileSnapshot requested) {
        if (requested == null) throw new IllegalArgumentException("peripheral profile is required");
        VirtualPeripheralServicesProfileSnapshot current = getOrCreate(scope);
        if (requested.policyVersion() != current.policyVersion()) {
            throw new IllegalStateException("PERIPHERAL_SERVICES_PROFILE_VERSION_CONFLICT:expected="
                    + current.policyVersion() + ":actual=" + requested.policyVersion());
        }
        VirtualPeripheralServicesProfileSnapshot updated = requested.withVersion(
                current.policyVersion() + 1L, System.currentTimeMillis());
        profiles.put(scope, updated);
        persistOrRestore(scope, current);
        return updated;
    }

    synchronized VirtualPeripheralServicesProfileSnapshot reset(
            VirtualSystemServiceStore.Scope scope) {
        VirtualPeripheralServicesProfileSnapshot current = profiles.get(scope);
        long version = current == null ? 1L : current.policyVersion() + 1L;
        VirtualPeripheralServicesProfileSnapshot reset = VirtualPeripheralServicesDefaults.create(
                scope.packageName(), scope.virtualUserId(), version, System.currentTimeMillis());
        profiles.put(scope, reset);
        persistOrRestore(scope, current);
        return reset;
    }

    synchronized void deleteScopeBestEffort(VirtualSystemServiceStore.Scope scope) {
        VirtualPeripheralServicesProfileSnapshot removed = profiles.remove(scope);
        if (removed == null) return;
        try {
            persist();
        } catch (RuntimeException error) {
            profiles.put(scope, removed);
            maintenanceWarning = "PERIPHERAL_SERVICES_DELETE_PERSIST_FAILED:" + error.getMessage();
        }
    }

    synchronized String maintenanceWarning() { return maintenanceWarning; }
    synchronized int scopeCount() { return profiles.size(); }

    private void load() {
        try {
            String payload = persistence.readPayload();
            if (payload != null) profiles.putAll(VirtualPeripheralServicesStoreCodec.decode(payload));
        } catch (RuntimeException error) {
            persistence.quarantine();
            maintenanceWarning = error.getMessage() == null
                    ? "PERIPHERAL_SERVICES_STORE_CORRUPT" : error.getMessage();
            profiles.clear();
        }
    }

    private void persistOrRestore(
            VirtualSystemServiceStore.Scope scope,
            VirtualPeripheralServicesProfileSnapshot previous) {
        try {
            persist();
        } catch (RuntimeException error) {
            if (previous == null) profiles.remove(scope); else profiles.put(scope, previous);
            throw error;
        }
    }

    private void persist() {
        if (profiles.size() > VirtualPeripheralServicesStoreCodec.MAX_SCOPES) {
            throw new IllegalStateException("Peripheral-services scope limit exceeded");
        }
        persistence.writePayload(VirtualPeripheralServicesStoreCodec.encode(profiles));
    }
}
