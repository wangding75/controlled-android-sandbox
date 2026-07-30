package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Package-Service-owned durable WebView/GMS/OEM/detection profiles. */
final class VirtualCompatibilityStore {
    private final VirtualCompatibilityStorePersistence persistence;
    private final Map<VirtualSystemServiceStore.Scope, VirtualCompatibilityProfileSnapshot> profiles =
            new LinkedHashMap<>();
    private String maintenanceWarning = "";

    VirtualCompatibilityStore(File filesDir) {
        if (filesDir == null) {
            throw new IllegalArgumentException("filesDir is required");
        }
        persistence = new VirtualCompatibilityStorePersistence(
                new File(new File(filesDir, "package-service"), "virtual-compatibility-v1.json"));
        load();
    }

    synchronized VirtualCompatibilityProfileSnapshot getOrCreate(
            VirtualSystemServiceStore.Scope scope) {
        VirtualCompatibilityProfileSnapshot value = profiles.get(scope);
        if (value != null) {
            return value;
        }
        value = VirtualCompatibilityDefaults.create(
                scope.packageName(), scope.virtualUserId(), 1L, System.currentTimeMillis());
        profiles.put(scope, value);
        persistOrRestore(scope, null);
        return value;
    }

    synchronized VirtualCompatibilityProfileSnapshot update(
            VirtualSystemServiceStore.Scope scope,
            VirtualCompatibilityProfileSnapshot requested) {
        if (requested == null) {
            throw new IllegalArgumentException("compatibility profile is required");
        }
        VirtualCompatibilityProfileSnapshot current = getOrCreate(scope);
        if (requested.policyVersion() != current.policyVersion()) {
            throw new IllegalStateException(
                    "COMPATIBILITY_PROFILE_VERSION_CONFLICT:expected="
                            + current.policyVersion()
                            + ":actual="
                            + requested.policyVersion());
        }
        VirtualCompatibilityProfileSnapshot updated = requested.withVersion(
                current.policyVersion() + 1L, System.currentTimeMillis());
        profiles.put(scope, updated);
        persistOrRestore(scope, current);
        return updated;
    }

    synchronized VirtualCompatibilityProfileSnapshot reset(
            VirtualSystemServiceStore.Scope scope) {
        VirtualCompatibilityProfileSnapshot current = profiles.get(scope);
        long version = current == null ? 1L : current.policyVersion() + 1L;
        VirtualCompatibilityProfileSnapshot reset = VirtualCompatibilityDefaults.create(
                scope.packageName(), scope.virtualUserId(), version, System.currentTimeMillis());
        profiles.put(scope, reset);
        persistOrRestore(scope, current);
        return reset;
    }

    synchronized void deleteScopeBestEffort(VirtualSystemServiceStore.Scope scope) {
        VirtualCompatibilityProfileSnapshot removed = profiles.remove(scope);
        if (removed == null) {
            return;
        }
        try {
            persist();
        } catch (RuntimeException error) {
            profiles.put(scope, removed);
            maintenanceWarning = "COMPATIBILITY_DELETE_PERSIST_FAILED:" + error.getMessage();
        }
    }

    synchronized String maintenanceWarning() {
        return maintenanceWarning;
    }

    synchronized int scopeCount() {
        return profiles.size();
    }

    private void load() {
        try {
            String payload = persistence.readPayload();
            if (payload != null) {
                profiles.putAll(VirtualCompatibilityStoreCodec.decode(payload));
            }
        } catch (RuntimeException error) {
            persistence.quarantine();
            maintenanceWarning = error.getMessage() == null
                    ? "COMPATIBILITY_STORE_CORRUPT"
                    : error.getMessage();
            profiles.clear();
        }
    }

    private void persistOrRestore(
            VirtualSystemServiceStore.Scope scope,
            VirtualCompatibilityProfileSnapshot previous) {
        try {
            persist();
        } catch (RuntimeException error) {
            if (previous == null) {
                profiles.remove(scope);
            } else {
                profiles.put(scope, previous);
            }
            throw error;
        }
    }

    private void persist() {
        if (profiles.size() > VirtualCompatibilityStoreCodec.MAX_SCOPES) {
            throw new IllegalStateException("Compatibility scope limit exceeded");
        }
        persistence.writePayload(VirtualCompatibilityStoreCodec.encode(profiles));
    }
}
