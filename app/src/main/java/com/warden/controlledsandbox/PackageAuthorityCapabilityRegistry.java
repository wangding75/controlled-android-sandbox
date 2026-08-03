package com.warden.controlledsandbox;

import android.os.IBinder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Generation-bound, PID-owned and death-linked Package Service role capability registry. */
final class PackageAuthorityCapabilityRegistry implements AutoCloseable {
    private final PackageCallerVerifier verifier;
    private final Map<String, Slot> slots = new HashMap<>();

    PackageAuthorityCapabilityRegistry(PackageCallerVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    void registerManagement(IBinder capability, long generation) {
        register(verifier.managementCaller(), capability, generation);
    }

    void registerRuntime(IBinder capability, long generation) {
        register(verifier.runtimeCaller(), capability, generation);
    }

    void requireManagement(IBinder capability, long generation) {
        require(verifier.managementCaller(), capability, generation,
                "PACKAGE_MANAGEMENT_CAPABILITY_DENIED");
    }

    void requireRuntime(IBinder capability, long generation) {
        require(verifier.runtimeCaller(), capability, generation,
                "PACKAGE_RUNTIME_CAPABILITY_DENIED");
    }

    private synchronized void register(PackageCallerVerifier.VerifiedCaller caller,
            IBinder capability, long generation) {
        requireCandidate(capability, generation);
        Slot existing = slots.get(caller.role);
        if (existing != null && existing.active) {
            if (existing.matches(caller, capability, generation)) return;
            throw new SecurityException("PACKAGE_AUTHORITY_ROLE_ALREADY_REGISTERED:" + caller.role);
        }
        if (existing != null && generation <= existing.generation) {
            throw new SecurityException("PACKAGE_AUTHORITY_GENERATION_NOT_ADVANCED:" + caller.role);
        }

        Slot replacement = new Slot(caller.uid, caller.pid, caller.role, capability, generation);
        replacement.deathRecipient = () -> retire(replacement);
        try {
            capability.linkToDeath(replacement.deathRecipient, 0);
        } catch (Exception error) {
            throw new SecurityException("PACKAGE_AUTHORITY_CAPABILITY_DEAD:" + caller.role, error);
        }
        if (!capability.isBinderAlive()) {
            try { capability.unlinkToDeath(replacement.deathRecipient, 0); }
            catch (Exception ignored) { }
            throw new SecurityException("PACKAGE_AUTHORITY_CAPABILITY_DEAD:" + caller.role);
        }
        replacement.active = true;
        slots.put(caller.role, replacement);
    }

    private synchronized void require(PackageCallerVerifier.VerifiedCaller caller,
            IBinder capability, long generation, String errorCode) {
        Slot slot = slots.get(caller.role);
        if (slot == null || !slot.active || !slot.matches(caller, capability, generation)
                || !slot.capability.isBinderAlive()) {
            if (slot != null && !slot.capability.isBinderAlive()) retire(slot);
            throw new SecurityException(errorCode + ":" + caller.role);
        }
    }

    private static void requireCandidate(IBinder capability, long generation) {
        if (capability == null || !capability.isBinderAlive()) {
            throw new SecurityException("PACKAGE_AUTHORITY_CAPABILITY_REQUIRED");
        }
        if (generation <= 0L) {
            throw new SecurityException("PACKAGE_AUTHORITY_GENERATION_REQUIRED");
        }
    }

    private synchronized void retire(Slot slot) {
        Slot current = slots.get(slot.role);
        if (current != slot) return;
        slot.active = false;
        try { slot.capability.unlinkToDeath(slot.deathRecipient, 0); }
        catch (Exception ignored) { }
    }

    @Override public synchronized void close() {
        for (Slot slot : slots.values()) {
            if (!slot.active) continue;
            slot.active = false;
            try { slot.capability.unlinkToDeath(slot.deathRecipient, 0); }
            catch (Exception ignored) { }
        }
    }

    private static final class Slot {
        final int ownerUid;
        final int ownerPid;
        final String role;
        final IBinder capability;
        final long generation;
        IBinder.DeathRecipient deathRecipient;
        boolean active;

        Slot(int ownerUid, int ownerPid, String role, IBinder capability, long generation) {
            this.ownerUid = ownerUid;
            this.ownerPid = ownerPid;
            this.role = role;
            this.capability = capability;
            this.generation = generation;
        }

        boolean matches(PackageCallerVerifier.VerifiedCaller caller,
                IBinder candidate, long candidateGeneration) {
            return ownerUid == caller.uid
                    && ownerPid == caller.pid
                    && generation == candidateGeneration
                    && capability.equals(candidate);
        }
    }
}
