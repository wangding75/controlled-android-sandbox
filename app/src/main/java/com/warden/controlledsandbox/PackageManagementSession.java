package com.warden.controlledsandbox;

import android.net.Uri;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IPackageManagementSession;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPolicyServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaCommunicationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import com.warden.controlledsandbox.contract.InstallSessionPage;
import com.warden.controlledsandbox.contract.VirtualPageRequest;
import java.io.File;

import static com.warden.controlledsandbox.PackageServiceDependencies.required;

final class PackageManagementSession extends IPackageManagementSession.Stub
        implements IBinder.DeathRecipient {
    private final PackageServiceDependencies dependencies;
    private final Object operationLock;
    private final SandboxPackageLifecycle lifecycle;
    private final VirtualPackageStateBuilder packageStateBuilder;
    private final HostPermissionStateResolver hostPermissions;
    private final VirtualSystemServiceStore systemServices;
    private final PackageProfileAuthority profiles;
    private final PackageProfileSession profileSession;
    private final InstallSessionPageAuthority installSessionPages;

    private final PackageManagementAuthorityGuard guard;
    private final IBinder clientToken;

    PackageManagementSession(PackageServiceDependencies dependencies,
            int ownerUid, int ownerPid, IBinder clientToken,
            IBinder authorityCapability, long authorityGeneration) {
        this.dependencies = java.util.Objects.requireNonNull(dependencies, "dependencies");
        operationLock = dependencies.operationLock;
        lifecycle = dependencies.lifecycle;
        packageStateBuilder = dependencies.packageStateBuilder;
        hostPermissions = dependencies.hostPermissions;
        systemServices = dependencies.systemServices;
        profiles = new PackageProfileAuthority(dependencies);
        installSessionPages = new InstallSessionPageAuthority(dependencies.filesDir);
        guard = new PackageManagementAuthorityGuard(ownerUid, ownerPid,
                dependencies.capabilityRegistry, authorityCapability, authorityGeneration);
        profileSession = new PackageProfileSession(profiles, guard);
        this.clientToken = clientToken;
    }
    @Override public PackageServiceResult loadCatalog() {
        return execute("loadCatalog", () -> PackageServiceResult.successCatalog(
                "loadCatalog", PackageServiceMapper.toSnapshot(
                        lifecycle.load(), dependencies.maintenanceWarning())));
    }
    @Override public PackageServiceResult importApk(String uri) {
        return execute("importApk", () -> PackageServiceResult.successRecord(
                "importApk", PackageServiceMapper.toSnapshot(
                        lifecycle.importApk(Uri.parse(required(uri, "uri")),
                                dependencies::stopGuestBeforeRevisionCommit))));
    }
    @Override public PackageServiceResult importApkWithNativeTrust(
            String uri, String nativeGuestTrust) {
        return execute("importApkWithNativeTrust", () -> PackageServiceResult.successRecord(
                "importApkWithNativeTrust", PackageServiceMapper.toSnapshot(
                        lifecycle.importApk(Uri.parse(required(uri, "uri")),
                                required(nativeGuestTrust, "nativeGuestTrust"),
                                dependencies::stopGuestBeforeRevisionCommit))));
    }
    @Override public PackageServiceResult importApkFile(String sourcePath) {
        return execute("importApkFile", () -> PackageServiceResult.successRecord(
                "importApkFile", PackageServiceMapper.toSnapshot(
                        lifecycle.importApkFile(new File(required(sourcePath, "sourcePath")),
                                dependencies::stopGuestBeforeRevisionCommit))));
    }
    @Override public PackageServiceResult importApkFileWithNativeTrust(
            String sourcePath, String nativeGuestTrust) {
        return execute("importApkFileWithNativeTrust", () -> PackageServiceResult.successRecord(
                "importApkFileWithNativeTrust", PackageServiceMapper.toSnapshot(
                        lifecycle.importApkFile(new File(required(sourcePath, "sourcePath")),
                                required(nativeGuestTrust, "nativeGuestTrust"),
                                dependencies::stopGuestBeforeRevisionCommit))));
    }
    public PackageServiceResult importInstalledApplication(
            String packageName, String nativeGuestTrust) {
        return execute("importInstalledApplication", () -> PackageServiceResult.successRecord(
                "importInstalledApplication", PackageServiceMapper.toSnapshot(
                        lifecycle.importInstalledApplication(required(packageName, "packageName"),
                                required(nativeGuestTrust, "nativeGuestTrust"),
                                dependencies::stopGuestBeforeRevisionCommit))));
    }
    @Override public PackageServiceResult createInstallSession(String expectedPackageName) {
        return execute("createInstallSession", () -> PackageServiceResult.successInt(
                "createInstallSession", lifecycle.createInstallSession(
                        expectedPackageName == null ? "" : expectedPackageName.trim())));
    }
    @Override public PackageServiceResult createInstallSessionWithParams(
            InstallSessionParamsSnapshot params) {
        return execute("createInstallSessionWithParams", () ->
                PackageServiceResult.successInstallSession("createInstallSessionWithParams",
                        lifecycle.createInstallSession(params)));
    }
    @Override public PackageServiceResult getInstallSessionInfo(int sessionId) {
        return execute("getInstallSessionInfo", () -> PackageServiceResult.successInstallSession(
                "getInstallSessionInfo", lifecycle.installSessionInfo(sessionId)));
    }
    @Override public PackageServiceResult listInstallSessions() {
        return execute("listInstallSessions", () -> PackageServiceResult.successInstallSessions(
                "listInstallSessions", installSessionPages.legacy(lifecycle)));
    }
    @Override public InstallSessionPage listInstallSessionsPage(VirtualPageRequest request) {
        requireOwner();
        synchronized (operationLock) {
            return installSessionPages.page(lifecycle, request);
        }
    }
    @Override public PackageServiceResult setInstallSessionProgress(int sessionId, float progress) {
        return execute("setInstallSessionProgress", () ->
                PackageServiceResult.successInstallSession("setInstallSessionProgress",
                        lifecycle.setInstallSessionProgress(sessionId, progress)));
    }
    @Override public PackageServiceResult retryInstallSession(int sessionId) {
        return execute("retryInstallSession", () -> PackageServiceResult.successInstallSession(
                "retryInstallSession", lifecycle.retryInstallSession(sessionId)));
    }
    @Override public PackageServiceResult addInstallArtifact(int sessionId, String sourceUri) {
        return execute("addInstallArtifact", () -> PackageServiceResult.successText(
                "addInstallArtifact", lifecycle.addInstallArtifact(sessionId,
                        Uri.parse(required(sourceUri, "sourceUri")))));
    }
    @Override public PackageServiceResult commitInstallSession(int sessionId) {
        return execute("commitInstallSession", () -> PackageServiceResult.successRecord(
                "commitInstallSession", PackageServiceMapper.toSnapshot(
                        lifecycle.commitInstallSession(sessionId,
                                dependencies::stopGuestBeforeRevisionCommit))));
    }
    @Override public PackageServiceResult abandonInstallSession(int sessionId) {
        return execute("abandonInstallSession", () -> {
            lifecycle.abandonInstallSession(sessionId);
            return PackageServiceResult.success("abandonInstallSession");
        });
    }
    @Override public PackageServiceResult findRecord(String packageName) {
        return execute("findRecord", () -> PackageServiceResult.successRecord(
                "findRecord", PackageServiceMapper.toSnapshot(
                        lifecycle.findRecord(required(packageName, "packageName")))));
    }
    @Override public PackageServiceResult getVirtualPackageState(String packageName,
                                                                  int virtualUserId) {
        return execute("getVirtualPackageState", () -> packageStateResult(
                "getVirtualPackageState", lifecycle.packagePolicy(
                        required(packageName, "packageName"), virtualUserId), virtualUserId));
    }
    @Override public PackageServiceResult setPermissionDecision(String packageName,
                                                                 int virtualUserId,
                                                                 String permission,
                                                                 String decision) {
        return execute("setPermissionDecision", () -> {
            String normalizedPackage = required(packageName, "packageName");
            String normalizedPermission = required(permission, "permission");
            SandboxPackagePolicyView current = lifecycle.packagePolicy(
                    normalizedPackage, virtualUserId);
            if (!packageStateBuilder.declaresPermission(current.record, normalizedPermission)) {
                throw new IllegalArgumentException(
                        "Permission is not declared by package: " + normalizedPermission);
            }
            SandboxPackagePolicyView updated = lifecycle.setPermissionDecision(
                    normalizedPackage, virtualUserId, normalizedPermission,
                    SandboxPolicyState.permissionDecisionValue(decision));
            return packageStateResult("setPermissionDecision", updated, virtualUserId);
        });
    }

    @Override public PackageServiceResult setAppOpMode(String packageName, int virtualUserId,
                                                        String opName, String mode) {
        return execute("setAppOpMode", () -> packageStateResult("setAppOpMode",
                lifecycle.setAppOpMode(required(packageName, "packageName"), virtualUserId,
                        required(opName, "opName"), SandboxPolicyState.appOpModeValue(mode)),
                virtualUserId));
    }

    @Override public PackageServiceResult setPackageEnabledSetting(String packageName,
                                                                    int virtualUserId,
                                                                    String state) {
        return execute("setPackageEnabledSetting", () -> packageStateResult(
                "setPackageEnabledSetting", lifecycle.setPackageState(
                        required(packageName, "packageName"), virtualUserId,
                        SandboxPolicyState.componentStateValue(state)), virtualUserId));
    }

    @Override public PackageServiceResult setComponentEnabledSetting(String packageName,
                                                                      int virtualUserId,
                                                                      String className,
                                                                      String state) {
        return execute("setComponentEnabledSetting", () -> {
            String normalizedPackage = required(packageName, "packageName");
            String normalizedClass = required(className, "className");
            SandboxPackagePolicyView current = lifecycle.packagePolicy(normalizedPackage, virtualUserId);
            if (!packageStateBuilder.declaresComponent(current.record, normalizedClass)) {
                throw new IllegalArgumentException("Component is not declared by package: " + normalizedClass);
            }
            return packageStateResult("setComponentEnabledSetting",
                    lifecycle.setComponentState(normalizedPackage, virtualUserId, normalizedClass,
                            SandboxPolicyState.componentStateValue(state)), virtualUserId);
        });
    }

    @Override public PackageServiceResult resetVirtualPolicy(String packageName,
                                                              int virtualUserId) {
        return execute("resetVirtualPolicy", () -> packageStateResult("resetVirtualPolicy",
                lifecycle.resetPolicy(required(packageName, "packageName"), virtualUserId),
                virtualUserId));
    }

    @Override public PackageServiceResult resolveRuntimePermission(long requestId,
                                                                    String outcome,
                                                                    String reason) {
        return execute("resolveRuntimePermission", () -> {
            RuntimePermissionRequestRecord current = lifecycle.permissionRequest(requestId);
            if (current == null) throw new IllegalArgumentException(
                    "Permission request does not exist: " + requestId);
            HostPermissionStateResolver.HostState host = hostPermissions.resolve(current.permission);
            SandboxCatalogState.PermissionRequestResult result = lifecycle.resolveRuntimePermission(
                    requestId, RuntimePermissionRequestRecord.state(outcome), host.grantedToHost,
                    reason, "HOST_MAIN");
            SandboxPackagePolicyView view = new SandboxPackagePolicyView(
                    result.state.findRecord(result.request.packageName),
                    result.state.policy(result.request.packageName, result.request.virtualUserId),
                    result.state);
            return PackageServiceResult.successPermissionRequest("resolveRuntimePermission",
                    PermissionServiceMapper.toSnapshot(result.request),
                    packageStateBuilder.build(view.record, view.policy.virtualUserId,
                            view.policy, view.catalog));
        });
    }

    @Override public PackageServiceResult revokeRuntimePermission(String packageName,
                                                                   int virtualUserId,
                                                                   String permission,
                                                                   String reason) {
        return execute("revokeRuntimePermission", () -> {
            String normalizedPackage = required(packageName, "packageName");
            String normalizedPermission = required(permission, "permission");
            SandboxPackagePolicyView current = lifecycle.packagePolicy(
                    normalizedPackage, virtualUserId);
            if (!packageStateBuilder.declaresPermission(current.record, normalizedPermission)) {
                throw new IllegalArgumentException(
                        "Permission is not declared by package: " + normalizedPermission);
            }
            PermissionCapabilityRegistry.Capability capability =
                    PermissionCapabilityRegistry.resolve(normalizedPermission);
            SandboxPackagePolicyView updated = lifecycle.revokeRuntimePermission(
                    normalizedPackage, virtualUserId, normalizedPermission,
                    capability.appOpName, reason, "HOST_MAIN");
            return packageStateResult("revokeRuntimePermission", updated, virtualUserId);
        });
    }

    @Override public PackageServiceResult listPendingPermissionRequests(String packageName,
                                                                        int virtualUserId) {
        return execute("listPendingPermissionRequests", () ->
                PackageServiceResult.successPermissionRequests(
                        "listPendingPermissionRequests",
                        PermissionServiceMapper.toRequestSnapshots(
                                lifecycle.pendingPermissionRequests(
                                        required(packageName, "packageName"), virtualUserId))));
    }

    @Override public PackageServiceResult listPermissionAudit(String packageName,
                                                               int virtualUserId, int limit) {
        return execute("listPermissionAudit", () ->
                PackageServiceResult.successPermissionAudit("listPermissionAudit",
                        PermissionServiceMapper.toAuditSnapshots(lifecycle.permissionAudit(
                                required(packageName, "packageName"), virtualUserId, limit))));
    }

    @Override
    public PackageServiceResult ensureInstance(String packageName, int virtualUserId) {
        return execute("ensureInstance", () -> {
            lifecycle.ensureInstance(required(packageName, "packageName"), virtualUserId);
            return PackageServiceResult.success("ensureInstance");
        });
    }

    @Override public PackageServiceResult createClone(String packageName) {
        return execute("createClone", () -> PackageServiceResult.successInt(
                "createClone", lifecycle.createClone(required(packageName, "packageName"))));
    }

    @Override public PackageServiceResult rollbackPackage(String packageName) {
        return execute("rollbackPackage", () -> {
            String normalized = required(packageName, "packageName");
            dependencies.stopGuestBeforeDestructiveOperation(normalized, 0);
            return PackageServiceResult.successText("rollbackPackage",
                    lifecycle.rollbackPackage(normalized).toJson().toString());
        });
    }

    @Override public PackageServiceResult resetIdentity(String packageName) {
        return execute("resetIdentity", () -> {
            String normalized = required(packageName, "packageName");
            dependencies.stopGuestBeforeDestructiveOperation(normalized, 0);
            return PackageServiceResult.successText("resetIdentity",
                    lifecycle.resetIdentity(normalized).toJson().toString());
        });
    }

    @Override public PackageServiceResult lifecycleTransaction(String packageName) {
        return execute("lifecycleTransaction", () -> {
            PackageLifecycleTransaction transaction = lifecycle.lifecycleTransaction(
                    required(packageName, "packageName"));
            return PackageServiceResult.successText("lifecycleTransaction",
                    transaction == null ? "" : transaction.toJson().toString());
        });
    }

    @Override public PackageServiceResult updateInstanceStatus(String packageName,
                                                                int virtualUserId,
                                                                String status) {
        return execute("updateInstanceStatus", () -> {
            lifecycle.updateInstanceStatus(required(packageName, "packageName"),
                    virtualUserId, required(status, "status"));
            return PackageServiceResult.success("updateInstanceStatus");
        });
    }

    @Override
    public PackageServiceResult deleteInstance(String packageName, int virtualUserId) {
        return execute("deleteInstance", () -> {
            String normalizedPackage = required(packageName, "packageName");
            // Destructive catalog/data mutation is behind a broker stop barrier. If the
            // generation cannot be stopped, execute() returns a failure and the authoritative
            // catalog remains unchanged.
            dependencies.stopGuestBeforeDestructiveOperation(normalizedPackage, virtualUserId);
            var catalog = lifecycle.deleteInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope =
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
            dependencies.deleteScopeBestEffort(scope);
            String warning = dependencies.maintenanceWarning();
            if (!warning.isEmpty()) {
                return PackageServiceResult.failure("deleteInstance", "DELETE_PARTIAL_CLEANUP",
                        warning);
            }
            return PackageServiceResult.successCatalog("deleteInstance",
                    PackageServiceMapper.toSnapshot(catalog, ""));
        });
    }

    @Override
    public PackageServiceResult clearInstanceData(String packageName, int virtualUserId) {
        return execute("clearInstanceData", () -> {
            String normalizedPackage = required(packageName, "packageName");
            dependencies.stopGuestBeforeDestructiveOperation(normalizedPackage, virtualUserId);
            lifecycle.clearInstanceData(normalizedPackage, virtualUserId);
            dependencies.deleteScopeBestEffort(
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId));
            String warning = dependencies.maintenanceWarning();
            if (!warning.isEmpty()) {
                return PackageServiceResult.failure("clearInstanceData", "CLEAR_PARTIAL_CLEANUP",
                        warning);
            }
            return PackageServiceResult.success("clearInstanceData");
        });
    }

    @Override public VirtualDeviceServiceProfileSnapshot getDeviceServiceProfile(
            String packageName, int virtualUserId) {
        return profileSession.getDeviceServiceProfile(packageName, virtualUserId);
    }
    @Override public VirtualDeviceServiceProfileSnapshot setDeviceServiceProfile(
            String packageName, int virtualUserId, VirtualDeviceServiceProfileSnapshot profile) {
        return profileSession.setDeviceServiceProfile(packageName, virtualUserId, profile);
    }
    @Override public VirtualDeviceServiceProfileSnapshot resetDeviceServiceProfile(
            String packageName, int virtualUserId) {
        return profileSession.resetDeviceServiceProfile(packageName, virtualUserId);
    }
    @Override public VirtualInteractionProfileSnapshot getInteractionProfile(
            String packageName, int virtualUserId) {
        return profileSession.getInteractionProfile(packageName, virtualUserId);
    }
    @Override public VirtualInteractionProfileSnapshot setInteractionProfile(
            String packageName, int virtualUserId, VirtualInteractionProfileSnapshot profile) {
        return profileSession.setInteractionProfile(packageName, virtualUserId, profile);
    }
    @Override public VirtualInteractionProfileSnapshot resetInteractionProfile(
            String packageName, int virtualUserId) {
        return profileSession.resetInteractionProfile(packageName, virtualUserId);
    }
    @Override public VirtualNetworkServiceProfileSnapshot getNetworkServiceProfile(
            String packageName, int virtualUserId) {
        return profileSession.getNetworkServiceProfile(packageName, virtualUserId);
    }
    @Override public VirtualNetworkServiceProfileSnapshot setNetworkServiceProfile(
            String packageName, int virtualUserId, VirtualNetworkServiceProfileSnapshot profile) {
        return profileSession.setNetworkServiceProfile(packageName, virtualUserId, profile);
    }
    @Override public VirtualNetworkServiceProfileSnapshot resetNetworkServiceProfile(
            String packageName, int virtualUserId) {
        return profileSession.resetNetworkServiceProfile(packageName, virtualUserId);
    }
    @Override public ApplicationEnvironmentProfileSnapshot getApplicationEnvironmentProfile(
            String packageName, int virtualUserId) {
        return profileSession.getApplicationEnvironmentProfile(packageName, virtualUserId);
    }
    @Override public ApplicationEnvironmentProfileSnapshot setApplicationEnvironmentProfile(
            String packageName, int virtualUserId, ApplicationEnvironmentProfileSnapshot profile) {
        return profileSession.setApplicationEnvironmentProfile(packageName, virtualUserId, profile);
    }
    @Override public ApplicationEnvironmentProfileSnapshot resetApplicationEnvironmentProfile(
            String packageName, int virtualUserId) {
        return profileSession.resetApplicationEnvironmentProfile(packageName, virtualUserId);
    }
    @Override public VirtualCompatibilityProfileSnapshot getCompatibilityProfile(
            String packageName, int virtualUserId) {
        return profileSession.getCompatibilityProfile(packageName, virtualUserId);
    }
    @Override public VirtualCompatibilityProfileSnapshot setCompatibilityProfile(
            String packageName, int virtualUserId, VirtualCompatibilityProfileSnapshot profile) {
        return profileSession.setCompatibilityProfile(packageName, virtualUserId, profile);
    }
    @Override public VirtualCompatibilityProfileSnapshot resetCompatibilityProfile(
            String packageName, int virtualUserId) {
        return profileSession.resetCompatibilityProfile(packageName, virtualUserId);
    }
    @Override public VirtualPolicyServicesProfileSnapshot getPolicyServicesProfile(
            String packageName, int virtualUserId) {
        return profileSession.getPolicyServicesProfile(packageName, virtualUserId);
    }
    @Override public VirtualPolicyServicesProfileSnapshot setPolicyServicesProfile(
            String packageName, int virtualUserId, VirtualPolicyServicesProfileSnapshot profile) {
        return profileSession.setPolicyServicesProfile(packageName, virtualUserId, profile);
    }
    @Override public VirtualPolicyServicesProfileSnapshot resetPolicyServicesProfile(
            String packageName, int virtualUserId) {
        return profileSession.resetPolicyServicesProfile(packageName, virtualUserId);
    }
    @Override public VirtualMediaCommunicationProfileSnapshot getMediaCommunicationProfile(
            String packageName, int virtualUserId) {
        return profileSession.getMediaCommunicationProfile(packageName, virtualUserId);
    }
    @Override public VirtualMediaCommunicationProfileSnapshot setMediaCommunicationProfile(
            String packageName, int virtualUserId, VirtualMediaCommunicationProfileSnapshot profile) {
        return profileSession.setMediaCommunicationProfile(packageName, virtualUserId, profile);
    }
    @Override public VirtualMediaCommunicationProfileSnapshot resetMediaCommunicationProfile(
            String packageName, int virtualUserId) {
        return profileSession.resetMediaCommunicationProfile(packageName, virtualUserId);
    }
    @Override public VirtualPeripheralServicesProfileSnapshot getPeripheralServicesProfile(
            String packageName, int virtualUserId) {
        return profileSession.getPeripheralServicesProfile(packageName, virtualUserId);
    }
    @Override public VirtualPeripheralServicesProfileSnapshot setPeripheralServicesProfile(
            String packageName, int virtualUserId, VirtualPeripheralServicesProfileSnapshot profile) {
        return profileSession.setPeripheralServicesProfile(packageName, virtualUserId, profile);
    }
    @Override public VirtualPeripheralServicesProfileSnapshot resetPeripheralServicesProfile(
            String packageName, int virtualUserId) {
        return profileSession.resetPeripheralServicesProfile(packageName, virtualUserId);
    }
    @Override public VirtualPrivilegedServicesProfileSnapshot getPrivilegedServicesProfile(
            String packageName, int virtualUserId) {
        return profileSession.getPrivilegedServicesProfile(packageName, virtualUserId);
    }
    @Override public VirtualPrivilegedServicesProfileSnapshot setPrivilegedServicesProfile(
            String packageName, int virtualUserId, VirtualPrivilegedServicesProfileSnapshot profile) {
        return profileSession.setPrivilegedServicesProfile(packageName, virtualUserId, profile);
    }
    @Override public VirtualPrivilegedServicesProfileSnapshot resetPrivilegedServicesProfile(
            String packageName, int virtualUserId) {
        return profileSession.resetPrivilegedServicesProfile(packageName, virtualUserId);
    }

    @Override public PackageServiceResult maintenanceStatus() {
        return execute("maintenanceStatus", () -> PackageServiceResult.successText(
                "maintenanceStatus", dependencies.maintenanceWarning()));
    }

    @Override public void close() {
        requireOwner();
        closeInternal();
    }

    @Override public void binderDied() { closeInternal(); }

    private PackageServiceResult packageStateResult(String operation,
                                                    SandboxPackagePolicyView view,
                                                    int virtualUserId) throws Exception {
        return PackageServiceResult.successPackageState(operation,
                packageStateBuilder.build(view.record, virtualUserId, view.policy, view.catalog));
    }

    private PackageServiceResult execute(String operation, PackageManagementOperation action) {
        requireOwner();
        synchronized (operationLock) {
            try {
                return action.run();
            } catch (Throwable error) {
                FatalErrorPolicy.rethrowIfFatal(error);
                android.util.Log.e("CS_PACKAGE", "FAIL operation=" + operation, error);
                String code = error instanceof NativeGuestPolicyException policyError
                        ? policyError.code() : error.getClass().getSimpleName();
                return PackageServiceResult.failure(operation, code, String.valueOf(error.getMessage()));
            }
        }
    }

    private void requireOwner() { guard.requireOwner(); }

    private void closeInternal() {
        installSessionPages.close();
        guard.close();
        try { clientToken.unlinkToDeath(this, 0); } catch (Exception ignored) { }
    }

}
