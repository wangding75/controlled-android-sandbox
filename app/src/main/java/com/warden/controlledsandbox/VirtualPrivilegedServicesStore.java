package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Package-Service-owned durable privileged-service profiles. */
final class VirtualPrivilegedServicesStore {
    private final VirtualPrivilegedServicesStorePersistence persistence;
    private final Map<VirtualSystemServiceStore.Scope, VirtualPrivilegedServicesProfileSnapshot> profiles =
            new LinkedHashMap<>();
    private String maintenanceWarning = "";

    VirtualPrivilegedServicesStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        persistence = new VirtualPrivilegedServicesStorePersistence(
                new File(new File(filesDir, "package-service"), "virtual-privileged-services-v1.json"));
        load();
    }

    synchronized VirtualPrivilegedServicesProfileSnapshot getOrCreate(
            VirtualSystemServiceStore.Scope scope) {
        VirtualPrivilegedServicesProfileSnapshot value = profiles.get(scope);
        if (value != null) return value;
        value = VirtualPrivilegedServicesDefaults.create(
                scope.packageName(), scope.virtualUserId(), 1L, System.currentTimeMillis());
        profiles.put(scope, value);
        persistOrRestore(scope, null);
        return value;
    }

    synchronized VirtualPrivilegedServicesProfileSnapshot update(
            VirtualSystemServiceStore.Scope scope,
            VirtualPrivilegedServicesProfileSnapshot requested) {
        if (requested == null) throw new IllegalArgumentException("privileged profile is required");
        VirtualPrivilegedServicesProfileSnapshot current = getOrCreate(scope);
        if (requested.policyVersion() != current.policyVersion()) {
            throw new IllegalStateException("PRIVILEGED_SERVICES_PROFILE_VERSION_CONFLICT:expected="
                    + current.policyVersion() + ":actual=" + requested.policyVersion());
        }
        VirtualPrivilegedServicesProfileSnapshot updated = requested.withVersion(
                current.policyVersion() + 1L, System.currentTimeMillis());
        profiles.put(scope, updated);
        persistOrRestore(scope, current);
        return updated;
    }

    synchronized VirtualPrivilegedServicesProfileSnapshot reset(
            VirtualSystemServiceStore.Scope scope) {
        VirtualPrivilegedServicesProfileSnapshot current = profiles.get(scope);
        long version = current == null ? 1L : current.policyVersion() + 1L;
        VirtualPrivilegedServicesProfileSnapshot reset = VirtualPrivilegedServicesDefaults.create(
                scope.packageName(), scope.virtualUserId(), version, System.currentTimeMillis());
        profiles.put(scope, reset);
        persistOrRestore(scope, current);
        return reset;
    }

    synchronized void deleteScopeBestEffort(VirtualSystemServiceStore.Scope scope) {
        VirtualPrivilegedServicesProfileSnapshot removed = profiles.remove(scope);
        if (removed == null) return;
        try {
            persist();
        } catch (RuntimeException error) {
            profiles.put(scope, removed);
            maintenanceWarning = "PRIVILEGED_SERVICES_DELETE_PERSIST_FAILED:" + error.getMessage();
        }
    }

    synchronized String maintenanceWarning() { return maintenanceWarning; }
    synchronized int scopeCount() { return profiles.size(); }

    private void load() {
        try {
            String payload = persistence.readPayload();
            if (payload != null) profiles.putAll(VirtualPrivilegedServicesStoreCodec.decode(payload));
        } catch (RuntimeException error) {
            persistence.quarantine();
            maintenanceWarning = error.getMessage() == null
                    ? "PRIVILEGED_SERVICES_STORE_CORRUPT" : error.getMessage();
            profiles.clear();
        }
    }

    private void persistOrRestore(VirtualSystemServiceStore.Scope scope,
            VirtualPrivilegedServicesProfileSnapshot previous) {
        try {
            persist();
        } catch (RuntimeException error) {
            if (previous == null) profiles.remove(scope); else profiles.put(scope, previous);
            throw error;
        }
    }

    private void persist() {
        if (profiles.size() > VirtualPrivilegedServicesStoreCodec.MAX_SCOPES) {
            throw new IllegalStateException("Privileged-services scope limit exceeded");
        }
        persistence.writePayload(VirtualPrivilegedServicesStoreCodec.encode(profiles));
    }
}
