package com.warden.controlledsandbox;

import android.os.Binder;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IPackageRuntimeQuerySession;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static com.warden.controlledsandbox.PackageServiceDependencies.required;

/** Read-only Package Authority session owned by a trusted Runtime Broker process. */
final class PackageRuntimeQuerySession extends IPackageRuntimeQuerySession.Stub
        implements IBinder.DeathRecipient {
    private final Object operationLock;
    private final SandboxPackageLifecycle lifecycle;
    private final VirtualPackageStateBuilder packageStateBuilder;
    private final PackageAuthorityCapabilityRegistry capabilityRegistry;
    private final IBinder authorityCapability;
    private final long authorityGeneration;
    private final RuntimePermissionSessionGuard guard;
    private final IBinder clientToken;

    PackageRuntimeQuerySession(PackageServiceDependencies dependencies,
            int ownerUid, int ownerPid, IBinder clientToken,
            IBinder authorityCapability, long authorityGeneration) {
        if (dependencies == null) throw new NullPointerException("dependencies");
        operationLock = dependencies.operationLock;
        lifecycle = dependencies.lifecycle;
        packageStateBuilder = dependencies.packageStateBuilder;
        capabilityRegistry = dependencies.capabilityRegistry;
        this.authorityCapability = authorityCapability;
        this.authorityGeneration = authorityGeneration;
        guard = new RuntimePermissionSessionGuard(ownerUid, ownerPid);
        this.clientToken = clientToken;
    }

    @Override public PackageServiceResult findRecord(String packageName) {
        return execute("findRecord", () -> PackageServiceResult.successRecord(
                "findRecord", PackageServiceMapper.toSnapshot(
                        lifecycle.findRecord(required(packageName, "packageName")))));
    }

    @Override public PackageServiceResult getVirtualPackageState(String packageName,
                                                                  int virtualUserId) {
        return execute("getVirtualPackageState", () -> {
            SandboxPackagePolicyView view = lifecycle.packagePolicy(
                    required(packageName, "packageName"), virtualUserId);
            return PackageServiceResult.successPackageState("getVirtualPackageState",
                    packageStateBuilder.build(view.record, virtualUserId,
                            view.policy, view.catalog));
        });
    }

    @Override public PackageServiceResult getVirtualPackageStates(int virtualUserId) {
        return execute("getVirtualPackageStates", () -> {
            if (virtualUserId < 0 || virtualUserId > 999) {
                throw new IllegalArgumentException("virtualUserId out of range");
            }
            SandboxCatalogState catalog = lifecycle.load();
            ArrayList<VirtualPackageStateSnapshot> states = new ArrayList<>();
            Set<String> installedPackages = new HashSet<>();
            for (SandboxInstance instance : catalog.instances()) {
                if (virtualUserId == instance.virtualUserId) installedPackages.add(instance.packageName);
            }
            for (SandboxRecord record : catalog.records()) {
                if (!installedPackages.contains(record.packageName)) continue;
                states.add(packageStateBuilder.build(record, virtualUserId,
                        catalog.policy(record.packageName, virtualUserId), catalog));
            }
            return PackageServiceResult.successPackageStates("getVirtualPackageStates", states,
                    PackageServiceMapper.toSnapshot(catalog, ""));
        });
    }

    @Override public void close() {
        requireRuntimeOwner();
        closeInternal();
    }

    @Override public void binderDied() { closeInternal(); }

    private PackageServiceResult execute(String operation, Operation action) {
        requireRuntimeOwner();
        synchronized (operationLock) {
            try {
                return action.run();
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                return PackageServiceResult.failure(operation, error.getClass().getSimpleName(),
                        String.valueOf(error.getMessage()));
            }
        }
    }

    private void requireRuntimeOwner() {
        guard.requireOwner(Binder.getCallingUid(), Binder.getCallingPid());
        capabilityRegistry.requireRuntime(authorityCapability, authorityGeneration);
    }

    private void closeInternal() {
        guard.close();
        try { clientToken.unlinkToDeath(this, 0); } catch (Exception ignored) { }
    }

    @FunctionalInterface
    private interface Operation { PackageServiceResult run() throws Exception; }
}
