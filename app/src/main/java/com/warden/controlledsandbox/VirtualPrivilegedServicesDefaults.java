package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualContextHubProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualGraphicsStatsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPersistentDataBlockProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSearchProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualStorageStatsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSystemUpdateProfileSnapshot;
import java.util.List;

/** Deterministic fail-closed defaults for privileged environment services. */
final class VirtualPrivilegedServicesDefaults {
    private VirtualPrivilegedServicesDefaults() { }

    static VirtualPrivilegedServicesProfileSnapshot create(
            String packageName, int virtualUserId, long version, long updatedAtMs) {
        String mode = VirtualLocationProfileSnapshot.MODE_STATIC;
        VirtualSearchProfileSnapshot search = new VirtualSearchProfileSnapshot(
                mode, false, false, "", "", List.of(), List.of(), 0);
        VirtualStorageStatsProfileSnapshot storage = new VirtualStorageStatsProfileSnapshot(
                mode, 8L * 1024L * 1024L * 1024L, 6L * 1024L * 1024L * 1024L,
                128L * 1024L * 1024L, 32L * 1024L * 1024L,
                16L * 1024L * 1024L, 4L * 1024L * 1024L, 0L, true, false);
        VirtualGraphicsStatsProfileSnapshot graphics = new VirtualGraphicsStatsProfileSnapshot(
                mode, false, false, 0, 0L, 0L, 0L);
        VirtualContextHubProfileSnapshot contextHub = new VirtualContextHubProfileSnapshot(
                mode, false, false, false, false, 0, List.of());
        VirtualPersistentDataBlockProfileSnapshot persistent =
                new VirtualPersistentDataBlockProfileSnapshot(
                        mode, false, false, false, 0, new byte[0], false, 0, true);
        VirtualSystemUpdateProfileSnapshot update = new VirtualSystemUpdateProfileSnapshot(
                mode, true, false, "UNKNOWN", "", "", "", 0, 0L);
        return new VirtualPrivilegedServicesProfileSnapshot(
                version, updatedAtMs, search, storage, graphics, contextHub, persistent, update);
    }
}
