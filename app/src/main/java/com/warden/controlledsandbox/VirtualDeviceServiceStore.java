package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Package-Service-owned durable virtual device-service profiles. */
final class VirtualDeviceServiceStore {
    private final VirtualDeviceServiceStorePersistence persistence;
    private final Map<VirtualSystemServiceStore.Scope, VirtualDeviceServiceProfileSnapshot> profiles =
            new LinkedHashMap<>();
    private String maintenanceWarning = "";

    VirtualDeviceServiceStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        persistence = new VirtualDeviceServiceStorePersistence(
                new File(new File(filesDir, "package-service"), "virtual-device-services-v1.json"));
        load();
    }

    synchronized VirtualDeviceServiceProfileSnapshot getOrCreate(VirtualSystemServiceStore.Scope scope) {
        VirtualDeviceServiceProfileSnapshot current = profiles.get(scope);
        if (current != null) return current;
        VirtualDeviceServiceProfileSnapshot created = VirtualDeviceServiceDefaults.create(
                scope.packageName(), scope.virtualUserId(), 1L, System.currentTimeMillis());
        profiles.put(scope, created);
        persistOrRestore(scope, null);
        return created;
    }

    synchronized VirtualDeviceServiceProfileSnapshot update(VirtualSystemServiceStore.Scope scope,
            VirtualDeviceServiceProfileSnapshot requested) {
        if (requested == null) throw new IllegalArgumentException("device profile is required");
        VirtualDeviceServiceProfileSnapshot current = getOrCreate(scope);
        if (requested.policyVersion() != current.policyVersion()) {
            throw new IllegalStateException("DEVICE_PROFILE_VERSION_CONFLICT:expected="
                    + current.policyVersion() + ":actual=" + requested.policyVersion());
        }
        VirtualDeviceServiceProfileSnapshot updated = requested.withVersion(
                current.policyVersion() + 1L, System.currentTimeMillis());
        profiles.put(scope, updated);
        persistOrRestore(scope, current);
        return updated;
    }

    synchronized VirtualDeviceServiceProfileSnapshot reset(VirtualSystemServiceStore.Scope scope) {
        VirtualDeviceServiceProfileSnapshot current = profiles.get(scope);
        long nextVersion = current == null ? 1L : current.policyVersion() + 1L;
        VirtualDeviceServiceProfileSnapshot reset = VirtualDeviceServiceDefaults.create(
                scope.packageName(), scope.virtualUserId(), nextVersion, System.currentTimeMillis());
        profiles.put(scope, reset);
        persistOrRestore(scope, current);
        return reset;
    }

    synchronized void deleteScopeBestEffort(VirtualSystemServiceStore.Scope scope) {
        VirtualDeviceServiceProfileSnapshot removed = profiles.remove(scope);
        if (removed == null) return;
        try { persist(); }
        catch (RuntimeException error) {
            profiles.put(scope, removed);
            maintenanceWarning = "DEVICE_PROFILE_DELETE_PERSIST_FAILED:" + String.valueOf(error.getMessage());
        }
    }

    synchronized String maintenanceWarning() { return maintenanceWarning; }
    synchronized int scopeCount() { return profiles.size(); }

    private void load() {
        try {
            String payload = persistence.readPayload();
            if (payload == null) return;
            profiles.putAll(VirtualDeviceServiceStoreCodec.decode(payload));
        } catch (RuntimeException error) {
            persistence.quarantine();
            maintenanceWarning = error.getMessage() == null
                    ? "DEVICE_PROFILE_STORE_CORRUPT" : error.getMessage();
            profiles.clear();
        }
    }
    private void persistOrRestore(VirtualSystemServiceStore.Scope scope,
            VirtualDeviceServiceProfileSnapshot previous) {
        try { persist(); }
        catch (RuntimeException error) {
            if (previous == null) profiles.remove(scope); else profiles.put(scope, previous);
            throw error;
        }
    }
    private void persist() {
        if (profiles.size() > VirtualDeviceServiceStoreCodec.MAX_SCOPES) {
            throw new IllegalStateException("Device-profile scope limit exceeded");
        }
        persistence.writePayload(VirtualDeviceServiceStoreCodec.encode(profiles));
    }
}
