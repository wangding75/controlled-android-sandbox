package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Package-Service-owned durable connectivity/DNS/proxy/VPN profiles. */
final class VirtualNetworkServiceStore {
    private final VirtualNetworkServiceStorePersistence persistence;
    private final Map<VirtualSystemServiceStore.Scope, VirtualNetworkServiceProfileSnapshot> profiles =
            new LinkedHashMap<>();
    private String maintenanceWarning = "";

    VirtualNetworkServiceStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        persistence = new VirtualNetworkServiceStorePersistence(
                new File(new File(filesDir, "package-service"), "virtual-network-services-v1.json"));
        load();
    }

    synchronized VirtualNetworkServiceProfileSnapshot getOrCreate(VirtualSystemServiceStore.Scope scope) {
        VirtualNetworkServiceProfileSnapshot current = profiles.get(scope);
        if (current != null) return current;
        VirtualNetworkServiceProfileSnapshot created = VirtualNetworkServiceDefaults.create(
                scope.packageName(), scope.virtualUserId(), 1L, System.currentTimeMillis());
        profiles.put(scope, created);
        persistOrRestore(scope, null);
        return created;
    }

    synchronized VirtualNetworkServiceProfileSnapshot update(VirtualSystemServiceStore.Scope scope,
            VirtualNetworkServiceProfileSnapshot requested) {
        if (requested == null) throw new IllegalArgumentException("network profile is required");
        VirtualNetworkServiceProfileSnapshot current = getOrCreate(scope);
        if (requested.policyVersion() != current.policyVersion()) {
            throw new IllegalStateException("NETWORK_PROFILE_VERSION_CONFLICT:expected="
                    + current.policyVersion() + ":actual=" + requested.policyVersion());
        }
        VirtualNetworkServiceProfileSnapshot updated = requested.withVersion(
                current.policyVersion() + 1L, System.currentTimeMillis());
        profiles.put(scope, updated);
        persistOrRestore(scope, current);
        return updated;
    }

    synchronized VirtualNetworkServiceProfileSnapshot reset(VirtualSystemServiceStore.Scope scope) {
        VirtualNetworkServiceProfileSnapshot current = profiles.get(scope);
        long next = current == null ? 1L : current.policyVersion() + 1L;
        VirtualNetworkServiceProfileSnapshot reset = VirtualNetworkServiceDefaults.create(
                scope.packageName(), scope.virtualUserId(), next, System.currentTimeMillis());
        profiles.put(scope, reset);
        persistOrRestore(scope, current);
        return reset;
    }

    synchronized void deleteScopeBestEffort(VirtualSystemServiceStore.Scope scope) {
        VirtualNetworkServiceProfileSnapshot removed = profiles.remove(scope);
        if (removed == null) return;
        try { persist(); }
        catch (RuntimeException error) {
            profiles.put(scope, removed);
            maintenanceWarning = "NETWORK_PROFILE_DELETE_PERSIST_FAILED:" + String.valueOf(error.getMessage());
        }
    }

    synchronized String maintenanceWarning() { return maintenanceWarning; }
    synchronized int scopeCount() { return profiles.size(); }

    private void load() {
        try {
            String payload = persistence.readPayload();
            if (payload != null) profiles.putAll(VirtualNetworkServiceStoreCodec.decode(payload));
        } catch (RuntimeException error) {
            persistence.quarantine();
            maintenanceWarning = error.getMessage() == null ? "NETWORK_PROFILE_STORE_CORRUPT" : error.getMessage();
            profiles.clear();
        }
    }
    private void persistOrRestore(VirtualSystemServiceStore.Scope scope,
            VirtualNetworkServiceProfileSnapshot previous) {
        try { persist(); }
        catch (RuntimeException error) {
            if (previous == null) profiles.remove(scope); else profiles.put(scope, previous);
            throw error;
        }
    }
    private void persist() {
        if (profiles.size() > VirtualNetworkServiceStoreCodec.MAX_SCOPES) {
            throw new IllegalStateException("Network-profile scope limit exceeded");
        }
        persistence.writePayload(VirtualNetworkServiceStoreCodec.encode(profiles));
    }
}
