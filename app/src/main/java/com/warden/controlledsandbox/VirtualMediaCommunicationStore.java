package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualMediaCommunicationProfileSnapshot;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Package-Service-owned durable media/communication/environment profiles. */
final class VirtualMediaCommunicationStore {
    private final VirtualMediaCommunicationStorePersistence persistence;
    private final Map<VirtualSystemServiceStore.Scope, VirtualMediaCommunicationProfileSnapshot> profiles =
            new LinkedHashMap<>();
    private String maintenanceWarning = "";

    VirtualMediaCommunicationStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        persistence = new VirtualMediaCommunicationStorePersistence(
                new File(new File(filesDir, "package-service"), "virtual-media-communication-v1.json"));
        load();
    }

    synchronized VirtualMediaCommunicationProfileSnapshot getOrCreate(VirtualSystemServiceStore.Scope scope) {
        VirtualMediaCommunicationProfileSnapshot value = profiles.get(scope);
        if (value != null) return value;
        value = VirtualMediaCommunicationDefaults.create(
                scope.packageName(), scope.virtualUserId(), 1L, System.currentTimeMillis());
        profiles.put(scope, value);
        persistOrRestore(scope, null);
        return value;
    }

    synchronized VirtualMediaCommunicationProfileSnapshot update(
            VirtualSystemServiceStore.Scope scope, VirtualMediaCommunicationProfileSnapshot requested) {
        if (requested == null) throw new IllegalArgumentException("media communication profile is required");
        VirtualMediaCommunicationProfileSnapshot current = getOrCreate(scope);
        if (requested.policyVersion() != current.policyVersion()) {
            throw new IllegalStateException("MEDIA_COMMUNICATION_PROFILE_VERSION_CONFLICT:expected="
                    + current.policyVersion() + ":actual=" + requested.policyVersion());
        }
        VirtualMediaCommunicationProfileSnapshot updated = requested.withVersion(
                current.policyVersion() + 1L, System.currentTimeMillis());
        profiles.put(scope, updated);
        persistOrRestore(scope, current);
        return updated;
    }

    synchronized VirtualMediaCommunicationProfileSnapshot reset(VirtualSystemServiceStore.Scope scope) {
        VirtualMediaCommunicationProfileSnapshot current = profiles.get(scope);
        long version = current == null ? 1L : current.policyVersion() + 1L;
        VirtualMediaCommunicationProfileSnapshot reset = VirtualMediaCommunicationDefaults.create(
                scope.packageName(), scope.virtualUserId(), version, System.currentTimeMillis());
        profiles.put(scope, reset);
        persistOrRestore(scope, current);
        return reset;
    }

    synchronized void deleteScopeBestEffort(VirtualSystemServiceStore.Scope scope) {
        VirtualMediaCommunicationProfileSnapshot removed = profiles.remove(scope);
        if (removed == null) return;
        try { persist(); }
        catch (RuntimeException error) {
            profiles.put(scope, removed);
            maintenanceWarning = "MEDIA_COMMUNICATION_DELETE_PERSIST_FAILED:" + error.getMessage();
        }
    }

    synchronized String maintenanceWarning() { return maintenanceWarning; }
    synchronized int scopeCount() { return profiles.size(); }

    private void load() {
        try {
            String payload = persistence.readPayload();
            if (payload != null) profiles.putAll(VirtualMediaCommunicationStoreCodec.decode(payload));
        } catch (RuntimeException error) {
            persistence.quarantine();
            maintenanceWarning = error.getMessage() == null
                    ? "MEDIA_COMMUNICATION_STORE_CORRUPT" : error.getMessage();
            profiles.clear();
        }
    }

    private void persistOrRestore(VirtualSystemServiceStore.Scope scope,
            VirtualMediaCommunicationProfileSnapshot previous) {
        try { persist(); }
        catch (RuntimeException error) {
            if (previous == null) profiles.remove(scope); else profiles.put(scope, previous);
            throw error;
        }
    }

    private void persist() {
        if (profiles.size() > VirtualMediaCommunicationStoreCodec.MAX_SCOPES) {
            throw new IllegalStateException("Media communication scope limit exceeded");
        }
        persistence.writePayload(VirtualMediaCommunicationStoreCodec.encode(profiles));
    }
}
