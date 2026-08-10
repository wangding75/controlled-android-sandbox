package com.warden.controlledsandbox;

import android.os.Binder;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IHostJobCallback;
import com.warden.controlledsandbox.contract.IPackageManagementSession;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.IRuntimePermissionSession;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import java.util.Objects;

/** Capability-aware Binder entry point owned by PackageManagementService. */
final class PackageServiceBinder extends IPackageService.Stub {
    private final PackageServiceDependencies dependencies;

    PackageServiceBinder(PackageServiceDependencies dependencies) {
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    }

    @Override public IPackageManagementSession openManagementSession(IBinder clientToken) {
        throw new SecurityException(
                com.warden.controlledsandbox.contract.PackageAuthorityCapabilityContract
                        .MANAGEMENT_CAPABILITY_REQUIRED);
    }

    @Override public IRuntimePermissionSession openRuntimePermissionSession(IBinder clientToken) {
        throw new SecurityException(
                com.warden.controlledsandbox.contract.PackageAuthorityCapabilityContract
                        .RUNTIME_CAPABILITY_REQUIRED);
    }

    @Override public IVirtualSystemServiceSession openVirtualSystemServiceSession(
            IBinder clientToken, String packageName, int virtualUserId, int virtualUid,
            String processName, long generation, String packageRevision) {
        throw new SecurityException(
                com.warden.controlledsandbox.contract.PackageAuthorityCapabilityContract
                        .RUNTIME_CAPABILITY_REQUIRED);
    }

    @Override public boolean startVirtualJob(VirtualJobParametersSnapshot parameters,
            IHostJobCallback callback) {
        throw new SecurityException(
                com.warden.controlledsandbox.contract.PackageAuthorityCapabilityContract
                        .RUNTIME_CAPABILITY_REQUIRED);
    }

    @Override public boolean stopVirtualJob(int hostJobId, int stopReason,
            int internalStopReason, String debugStopReason) {
        throw new SecurityException(
                com.warden.controlledsandbox.contract.PackageAuthorityCapabilityContract
                        .RUNTIME_CAPABILITY_REQUIRED);
    }

    @Override public void registerManagementCapability(IBinder capability,
            long capabilityGeneration) {
        throw new SecurityException("PACKAGE_AUTHORITY_PUBLIC_BOOTSTRAP_DISABLED");
    }

    @Override public void registerRuntimeCapability(IBinder capability,
            long capabilityGeneration) {
        throw new SecurityException("PACKAGE_AUTHORITY_PUBLIC_BOOTSTRAP_DISABLED");
    }

    @Override public IPackageManagementSession openManagementSessionWithCapability(
            IBinder clientToken, IBinder capability, long capabilityGeneration) {
        requireClientToken(clientToken, "PACKAGE_MANAGEMENT_CLIENT_TOKEN_REQUIRED");
        dependencies.capabilityRegistry.requireManagement(capability, capabilityGeneration);
        int ownerUid = Binder.getCallingUid();
        int ownerPid = Binder.getCallingPid();
        PackageManagementSession session = new PackageManagementSession(
                dependencies, ownerUid, ownerPid, clientToken,
                capability, capabilityGeneration);
        linkClientDeath(clientToken, session,
                "PACKAGE_MANAGEMENT_CLIENT_TOKEN_DEAD");
        return session;
    }

    @Override public IRuntimePermissionSession openRuntimePermissionSessionWithCapability(
            IBinder clientToken, IBinder capability, long capabilityGeneration) {
        requireClientToken(clientToken, "RUNTIME_PERMISSION_CLIENT_TOKEN_REQUIRED");
        dependencies.capabilityRegistry.requireRuntime(capability, capabilityGeneration);
        int ownerUid = Binder.getCallingUid();
        int ownerPid = Binder.getCallingPid();
        PackageRuntimePermissionSession session = new PackageRuntimePermissionSession(
                dependencies, ownerUid, ownerPid, clientToken,
                capability, capabilityGeneration);
        linkClientDeath(clientToken, session,
                "RUNTIME_PERMISSION_CLIENT_TOKEN_DEAD");
        return session;
    }

    @Override public IVirtualSystemServiceSession openVirtualSystemServiceSessionWithCapability(
            IBinder clientToken, String packageName, int virtualUserId, int virtualUid,
            String processName, long generation, String packageRevision,
        IBinder capability, long capabilityGeneration) {
        requireClientToken(clientToken, "VIRTUAL_SYSTEM_SERVICE_CLIENT_TOKEN_REQUIRED");
        String authorityRole = dependencies.capabilityRegistry.requireRuntime(
                capability, capabilityGeneration);
        String normalizedPackage = PackageServiceDependencies.required(packageName, "packageName");
        synchronized (dependencies.operationLock) {
            boolean installed = false;
            try {
                SandboxCatalogState state = dependencies.lifecycle.load();
                for (SandboxInstance instance : state.instances()) {
                    if (normalizedPackage.equals(instance.packageName)
                            && virtualUserId == instance.virtualUserId) {
                        installed = true;
                        break;
                    }
                }
                SandboxRecord authoritative = state.findRecord(normalizedPackage);
                if (authoritative == null) {
                    throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_PACKAGE_NOT_INSTALLED");
                }
                String authoritativeRevision = com.warden.controlledsandbox.domain.session
                        .PackageRevision.of(authoritative.versionCode, authoritative.sha256)
                        .canonical();
                if (!authoritativeRevision.equals(packageRevision)) {
                    throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_REVISION_MISMATCH");
                }
                NativeGuestExecutionPolicy.requireRuntimeAllowed(authoritative);
            } catch (SecurityException error) {
                throw error;
            } catch (Exception error) {
                throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_SCOPE_LOOKUP_FAILED", error);
            }
            if (!installed) {
                throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_SCOPE_NOT_INSTALLED");
            }
        }
        PackageVirtualSystemServiceSession session = new PackageVirtualSystemServiceSession(
                dependencies, Binder.getCallingUid(), clientToken,
                new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId), virtualUid,
                PackageServiceDependencies.required(processName, "processName"), generation,
                PackageServiceDependencies.required(packageRevision, "packageRevision"),
                capability, capabilityGeneration, authorityRole);
        dependencies.systemServices.reserveClientRegistration(session);
        try {
            if (!session.linkClientDeathAfterReservation()) {
                throw new SecurityException(
                        "VIRTUAL_SYSTEM_SERVICE_CLIENT_TOKEN_DEAD_DURING_LINK");
            }
            dependencies.systemServices.commitClientRegistration(session);
        } catch (Exception error) {
            session.binderDied();
            if (error instanceof SecurityException securityException) throw securityException;
            throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_CLIENT_TOKEN_DEAD", error);
        }
        return session;
    }

    @Override public boolean startVirtualJobWithCapability(
            VirtualJobParametersSnapshot parameters, IHostJobCallback callback,
            IBinder capability, long capabilityGeneration) {
        dependencies.capabilityRegistry.requireRuntime(capability, capabilityGeneration);
        if (parameters == null || callback == null || callback.asBinder() == null
                || !callback.asBinder().isBinderAlive()) {
            throw new IllegalArgumentException(
                    "virtual job parameters and callback are required");
        }
        return dependencies.systemServices.startJob(
                parameters, callback, Binder.getCallingUid());
    }

    @Override public boolean stopVirtualJobWithCapability(int hostJobId, int stopReason,
            int internalStopReason, String debugStopReason, IBinder capability,
            long capabilityGeneration) {
        dependencies.capabilityRegistry.requireRuntime(capability, capabilityGeneration);
        if (hostJobId < 0) {
            throw new IllegalArgumentException("hostJobId must be non-negative");
        }
        return dependencies.systemServices.stopJob(
                hostJobId, stopReason, internalStopReason, debugStopReason);
    }

    private void requireClientToken(IBinder clientToken, String code) {
        if (clientToken == null || !clientToken.isBinderAlive()) {
            throw new SecurityException(code);
        }
    }

    private void linkClientDeath(IBinder clientToken, IBinder.DeathRecipient recipient,
            String code) {
        try {
            clientToken.linkToDeath(recipient, 0);
        } catch (Exception error) {
            throw new SecurityException(code, error);
        }
    }

}
