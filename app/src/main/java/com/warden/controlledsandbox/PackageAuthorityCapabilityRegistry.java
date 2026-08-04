package com.warden.controlledsandbox;

import android.os.IBinder;
import com.warden.controlledsandbox.contract.PackageAuthorityCapabilityContract;
import com.warden.controlledsandbox.contract.RuntimePeerIdentity;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Server-epoch, death-linked Package Service role capability registry.
 *
 * <p>Capabilities are installed only by PackageManagementService after Android binds an explicit,
 * non-exported bootstrap component. Public Binder callers can present a capability, but can never
 * register or replace one.</p>
 */
final class PackageAuthorityCapabilityRegistry implements AutoCloseable {
    private final PackageCallerVerifier verifier;
    private final Map<String, Slot> slots = new HashMap<>();
    private long nextServerEpoch = 1L;

    PackageAuthorityCapabilityRegistry(PackageCallerVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    void installManagement(IBinder capability, int ownerUid, int ownerPid) {
        install(PackageCallerVerifier.MANAGEMENT_ROLE, capability, ownerUid, ownerPid);
    }

    void installRuntime(IBinder capability, int ownerUid, int ownerPid) {
        install(PackageCallerVerifier.HOST_RUNTIME_ROLE, capability, ownerUid, ownerPid);
    }

    void installCompanionRuntime(String packageName, IBinder capability, int ownerUid, int ownerPid) {
        install(companionRole(packageName), capability, ownerUid, ownerPid);
    }

    void clearManagement(IBinder capability) {
        clear(PackageCallerVerifier.MANAGEMENT_ROLE, capability);
    }

    void clearRuntime(IBinder capability) {
        clear(PackageCallerVerifier.HOST_RUNTIME_ROLE, capability);
    }

    void clearCompanionRuntime(String packageName, IBinder capability) {
        clear(companionRole(packageName), capability);
    }

    void requireManagement(IBinder capability, long clientEpochMarker) {
        require(verifier.managementCaller(), capability, clientEpochMarker,
                "PACKAGE_MANAGEMENT_CAPABILITY_DENIED");
    }

    void requireRuntime(IBinder capability, long clientEpochMarker) {
        require(verifier.runtimeCaller(), capability, clientEpochMarker,
                "PACKAGE_RUNTIME_CAPABILITY_DENIED");
    }

    private synchronized void install(String role, IBinder capability,
            int ownerUid, int ownerPid) {
        requireCandidate(capability, ownerUid, ownerPid);
        Slot existing = slots.get(role);
        if (existing != null && existing.active && existing.capability.equals(capability)
                && existing.ownerUid == ownerUid && existing.ownerPid == ownerPid) return;
        if (existing != null) retire(existing);

        Slot replacement = new Slot(role, capability, nextServerEpoch++, ownerUid, ownerPid);
        replacement.deathRecipient = () -> retire(replacement);
        try {
            capability.linkToDeath(replacement.deathRecipient, 0);
        } catch (Exception error) {
            throw new SecurityException("PACKAGE_AUTHORITY_BOOTSTRAP_DEAD:" + role, error);
        }
        if (!capability.isBinderAlive()) {
            try { capability.unlinkToDeath(replacement.deathRecipient, 0); }
            catch (Exception ignored) { }
            throw new SecurityException("PACKAGE_AUTHORITY_BOOTSTRAP_DEAD:" + role);
        }
        replacement.active = true;
        slots.put(role, replacement);
    }

    private synchronized void require(PackageCallerVerifier.VerifiedCaller caller,
            IBinder capability, long clientEpochMarker, String errorCode) {
        if (clientEpochMarker != PackageAuthorityCapabilityContract.SERVER_MANAGED_EPOCH) {
            throw new SecurityException("PACKAGE_AUTHORITY_CLIENT_EPOCH_FORBIDDEN:" + caller.role);
        }
        Slot slot = slots.get(caller.role);
        if (slot == null || !slot.active || !slot.capability.equals(capability)
                || !slot.capability.isBinderAlive()) {
            if (slot != null && !slot.capability.isBinderAlive()) retire(slot);
            throw new SecurityException(errorCode + ":" + caller.role);
        }
        if (slot.ownerUid != caller.uid || slot.ownerPid != caller.pid) {
            throw new SecurityException(errorCode + ":PROCESS_OWNER_MISMATCH:" + caller.role);
        }
    }

    private static void requireCandidate(IBinder capability, int ownerUid, int ownerPid) {
        if (capability == null || !capability.isBinderAlive() || ownerUid < 0 || ownerPid <= 0) {
            throw new SecurityException("PACKAGE_AUTHORITY_BOOTSTRAP_REQUIRED");
        }
    }

    private static String companionRole(String packageName) {
        if (!RuntimePeerIdentity.isCompanionPackage(packageName)) {
            throw new IllegalArgumentException("companion package is invalid");
        }
        return PackageCallerVerifier.COMPANION_RUNTIME_ROLE_PREFIX + packageName;
    }

    private synchronized void clear(String role, IBinder capability) {
        Slot slot = slots.get(role);
        if (slot != null && slot.capability.equals(capability)) retire(slot);
    }

    private synchronized void retire(Slot slot) {
        Slot current = slots.get(slot.role);
        if (current != slot) return;
        slots.remove(slot.role);
        slot.active = false;
        try { slot.capability.unlinkToDeath(slot.deathRecipient, 0); }
        catch (Exception ignored) { }
    }

    @Override public synchronized void close() {
        for (Slot slot : slots.values().toArray(new Slot[0])) retire(slot);
    }

    private static final class Slot {
        final String role;
        final IBinder capability;
        final long serverEpoch;
        final int ownerUid;
        final int ownerPid;
        IBinder.DeathRecipient deathRecipient;
        boolean active;

        Slot(String role, IBinder capability, long serverEpoch,
                int ownerUid, int ownerPid) {
            this.role = role;
            this.capability = capability;
            this.serverEpoch = serverEpoch;
            this.ownerUid = ownerUid;
            this.ownerPid = ownerPid;
        }
    }
}
