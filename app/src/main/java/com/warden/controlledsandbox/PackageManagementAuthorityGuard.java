package com.warden.controlledsandbox;

import android.os.Binder;
import android.os.IBinder;
import java.util.Objects;

/** Revalidates both direct session ownership and the live management role capability. */
final class PackageManagementAuthorityGuard {
    private final ManagementSessionGuard owner;
    private final PackageAuthorityCapabilityRegistry registry;
    private final IBinder capability;
    private final long generation;

    PackageManagementAuthorityGuard(int ownerUid, int ownerPid,
            PackageAuthorityCapabilityRegistry registry, IBinder capability, long generation) {
        owner = new ManagementSessionGuard(ownerUid, ownerPid);
        this.registry = Objects.requireNonNull(registry, "registry");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.generation = generation;
    }

    void requireOwner() {
        owner.requireOwner(Binder.getCallingUid(), Binder.getCallingPid());
        registry.requireManagement(capability, generation);
    }

    void close() { owner.close(); }
}
