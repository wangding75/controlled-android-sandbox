package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Package-Service-owned durable window/input/display profiles. */
final class VirtualInteractionStore {
    private final VirtualInteractionStorePersistence persistence;
    private final Map<VirtualSystemServiceStore.Scope, VirtualInteractionProfileSnapshot> profiles =
            new LinkedHashMap<>();
    private String maintenanceWarning = "";

    VirtualInteractionStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        persistence = new VirtualInteractionStorePersistence(
                new File(new File(filesDir, "package-service"), "virtual-interactions-v1.json"));
        load();
    }

    synchronized VirtualInteractionProfileSnapshot getOrCreate(VirtualSystemServiceStore.Scope scope) {
        VirtualInteractionProfileSnapshot current = profiles.get(scope);
        if (current != null) return current;
        VirtualInteractionProfileSnapshot created = VirtualInteractionDefaults.create(
                scope.packageName(), scope.virtualUserId(), 1L, System.currentTimeMillis());
        profiles.put(scope, created);
        persistOrRestore(scope, null);
        return created;
    }

    synchronized VirtualInteractionProfileSnapshot update(VirtualSystemServiceStore.Scope scope,
            VirtualInteractionProfileSnapshot requested) {
        if (requested == null) throw new IllegalArgumentException("interaction profile is required");
        VirtualInteractionProfileSnapshot current = getOrCreate(scope);
        if (requested.policyVersion() != current.policyVersion()) {
            throw new IllegalStateException("INTERACTION_PROFILE_VERSION_CONFLICT:expected="
                    + current.policyVersion() + ":actual=" + requested.policyVersion());
        }
        VirtualInteractionProfileSnapshot updated = requested.withVersion(
                current.policyVersion() + 1L, System.currentTimeMillis());
        profiles.put(scope, updated);
        persistOrRestore(scope, current);
        return updated;
    }

    synchronized VirtualInteractionProfileSnapshot reset(VirtualSystemServiceStore.Scope scope) {
        VirtualInteractionProfileSnapshot current = profiles.get(scope);
        long nextVersion = current == null ? 1L : current.policyVersion() + 1L;
        VirtualInteractionProfileSnapshot reset = VirtualInteractionDefaults.create(
                scope.packageName(), scope.virtualUserId(), nextVersion, System.currentTimeMillis());
        profiles.put(scope, reset);
        persistOrRestore(scope, current);
        return reset;
    }

    synchronized void deleteScopeBestEffort(VirtualSystemServiceStore.Scope scope) {
        VirtualInteractionProfileSnapshot removed = profiles.remove(scope);
        if (removed == null) return;
        try { persist(); }
        catch (RuntimeException error) {
            profiles.put(scope, removed);
            maintenanceWarning = "INTERACTION_PROFILE_DELETE_PERSIST_FAILED:"
                    + String.valueOf(error.getMessage());
        }
    }

    synchronized String maintenanceWarning() { return maintenanceWarning; }
    synchronized int scopeCount() { return profiles.size(); }

    private void load() {
        try {
            String payload = persistence.readPayload();
            if (payload == null) return;
            profiles.putAll(VirtualInteractionStoreCodec.decode(payload));
        } catch (RuntimeException error) {
            persistence.quarantine();
            maintenanceWarning = error.getMessage() == null
                    ? "INTERACTION_PROFILE_STORE_CORRUPT" : error.getMessage();
            profiles.clear();
        }
    }
    private void persistOrRestore(VirtualSystemServiceStore.Scope scope,
            VirtualInteractionProfileSnapshot previous) {
        try { persist(); }
        catch (RuntimeException error) {
            if (previous == null) profiles.remove(scope); else profiles.put(scope, previous);
            throw error;
        }
    }
    private void persist() {
        if (profiles.size() > VirtualInteractionStoreCodec.MAX_SCOPES) {
            throw new IllegalStateException("Interaction-profile scope limit exceeded");
        }
        persistence.writePayload(VirtualInteractionStoreCodec.encode(profiles));
    }
}
