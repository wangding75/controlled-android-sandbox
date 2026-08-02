package com.warden.controlledsandbox;

import android.net.Uri;
import android.os.Binder;
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
import java.io.File;

import static com.warden.controlledsandbox.PackageServiceDependencies.required;

final class PackageManagementSession extends IPackageManagementSession.Stub
        implements IBinder.DeathRecipient {
    private final PackageServiceDependencies dependencies;
    private final Object operationLock;
    private final SandboxPackageLifecycle lifecycle;
    private final PackageCallerVerifier callerVerifier;
    private final VirtualPackageStateBuilder packageStateBuilder;
    private final HostPermissionStateResolver hostPermissions;
    private final VirtualSystemServiceStore systemServices;
    private final PackageProfileAuthority profiles;

    private final ManagementSessionGuard guard;
    private final IBinder clientToken;

    PackageManagementSession(PackageServiceDependencies dependencies,
            int ownerUid, int ownerPid, IBinder clientToken) {
        this.dependencies = java.util.Objects.requireNonNull(dependencies, "dependencies");
        operationLock = dependencies.operationLock;
        lifecycle = dependencies.lifecycle;
        callerVerifier = dependencies.callerVerifier;
        packageStateBuilder = dependencies.packageStateBuilder;
        hostPermissions = dependencies.hostPermissions;
        systemServices = dependencies.systemServices;
        profiles = new PackageProfileAuthority(dependencies);
        guard = new ManagementSessionGuard(ownerUid, ownerPid);
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
                        lifecycle.importApk(Uri.parse(required(uri, "uri"))))));
    }

    @Override public PackageServiceResult importApkFile(String sourcePath) {
        return execute("importApkFile", () -> PackageServiceResult.successRecord(
                "importApkFile", PackageServiceMapper.toSnapshot(
                        lifecycle.importApkFile(new File(required(sourcePath, "sourcePath"))))));
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
                "listInstallSessions", lifecycle.installSessions()));
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
                        lifecycle.commitInstallSession(sessionId))));
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
            var catalog = lifecycle.deleteInstance(normalizedPackage, virtualUserId);
            VirtualSystemServiceStore.Scope scope =
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
            dependencies.deleteScopeBestEffort(scope);
            return PackageServiceResult.successCatalog("deleteInstance",
                    PackageServiceMapper.toSnapshot(catalog, dependencies.maintenanceWarning()));
        });
    }

    @Override
    public VirtualDeviceServiceProfileSnapshot getDeviceServiceProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.getDeviceServiceProfile(packageName, virtualUserId);
    }

    @Override
    public VirtualDeviceServiceProfileSnapshot setDeviceServiceProfile(
            String packageName, int virtualUserId, VirtualDeviceServiceProfileSnapshot profile) {
        requireOwner();
        return profiles.setDeviceServiceProfile(packageName, virtualUserId, profile);
    }

    @Override
    public VirtualDeviceServiceProfileSnapshot resetDeviceServiceProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.resetDeviceServiceProfile(packageName, virtualUserId);
    }

    @Override
    public VirtualInteractionProfileSnapshot getInteractionProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.getInteractionProfile(packageName, virtualUserId);
    }

    @Override
    public VirtualInteractionProfileSnapshot setInteractionProfile(
            String packageName, int virtualUserId, VirtualInteractionProfileSnapshot profile) {
        requireOwner();
        return profiles.setInteractionProfile(packageName, virtualUserId, profile);
    }

    @Override
    public VirtualInteractionProfileSnapshot resetInteractionProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.resetInteractionProfile(packageName, virtualUserId);
    }

    @Override
    public VirtualNetworkServiceProfileSnapshot getNetworkServiceProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.getNetworkServiceProfile(packageName, virtualUserId);
    }

    @Override
    public VirtualNetworkServiceProfileSnapshot setNetworkServiceProfile(
            String packageName, int virtualUserId, VirtualNetworkServiceProfileSnapshot profile) {
        requireOwner();
        return profiles.setNetworkServiceProfile(packageName, virtualUserId, profile);
    }

    @Override
    public VirtualNetworkServiceProfileSnapshot resetNetworkServiceProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.resetNetworkServiceProfile(packageName, virtualUserId);
    }

    @Override
    public ApplicationEnvironmentProfileSnapshot getApplicationEnvironmentProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.getApplicationEnvironmentProfile(packageName, virtualUserId);
    }

    @Override
    public ApplicationEnvironmentProfileSnapshot setApplicationEnvironmentProfile(
            String packageName, int virtualUserId, ApplicationEnvironmentProfileSnapshot profile) {
        requireOwner();
        return profiles.setApplicationEnvironmentProfile(packageName, virtualUserId, profile);
    }

    @Override
    public ApplicationEnvironmentProfileSnapshot resetApplicationEnvironmentProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.resetApplicationEnvironmentProfile(packageName, virtualUserId);
    }

    @Override
    public VirtualCompatibilityProfileSnapshot getCompatibilityProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.getCompatibilityProfile(packageName, virtualUserId);
    }
    @Override
    public VirtualCompatibilityProfileSnapshot setCompatibilityProfile(
            String packageName, int virtualUserId, VirtualCompatibilityProfileSnapshot profile) {
        requireOwner();
        return profiles.setCompatibilityProfile(packageName, virtualUserId, profile);
    }
    @Override
    public VirtualCompatibilityProfileSnapshot resetCompatibilityProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.resetCompatibilityProfile(packageName, virtualUserId);
    }
    @Override
    public VirtualPolicyServicesProfileSnapshot getPolicyServicesProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.getPolicyServicesProfile(packageName, virtualUserId);
    }
    @Override
    public VirtualPolicyServicesProfileSnapshot setPolicyServicesProfile(
            String packageName, int virtualUserId, VirtualPolicyServicesProfileSnapshot profile) {
        requireOwner();
        return profiles.setPolicyServicesProfile(packageName, virtualUserId, profile);
    }
    @Override
    public VirtualPolicyServicesProfileSnapshot resetPolicyServicesProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.resetPolicyServicesProfile(packageName, virtualUserId);
    }
    @Override
    public VirtualMediaCommunicationProfileSnapshot getMediaCommunicationProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.getMediaCommunicationProfile(packageName, virtualUserId);
    }

    @Override
    public VirtualMediaCommunicationProfileSnapshot setMediaCommunicationProfile(
            String packageName, int virtualUserId, VirtualMediaCommunicationProfileSnapshot profile) {
        requireOwner();
        return profiles.setMediaCommunicationProfile(packageName, virtualUserId, profile);
    }

    @Override
    public VirtualMediaCommunicationProfileSnapshot resetMediaCommunicationProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.resetMediaCommunicationProfile(packageName, virtualUserId);
    }

    @Override
    public VirtualPeripheralServicesProfileSnapshot getPeripheralServicesProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.getPeripheralServicesProfile(packageName, virtualUserId);
    }

    @Override
    public VirtualPeripheralServicesProfileSnapshot setPeripheralServicesProfile(
            String packageName, int virtualUserId, VirtualPeripheralServicesProfileSnapshot profile) {
        requireOwner();
        return profiles.setPeripheralServicesProfile(packageName, virtualUserId, profile);
    }

    @Override
    public VirtualPeripheralServicesProfileSnapshot resetPeripheralServicesProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.resetPeripheralServicesProfile(packageName, virtualUserId);
    }

    @Override
    public VirtualPrivilegedServicesProfileSnapshot getPrivilegedServicesProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.getPrivilegedServicesProfile(packageName, virtualUserId);
    }

    @Override
    public VirtualPrivilegedServicesProfileSnapshot setPrivilegedServicesProfile(
            String packageName, int virtualUserId, VirtualPrivilegedServicesProfileSnapshot profile) {
        requireOwner();
        return profiles.setPrivilegedServicesProfile(packageName, virtualUserId, profile);
    }

    @Override
    public VirtualPrivilegedServicesProfileSnapshot resetPrivilegedServicesProfile(String packageName, int virtualUserId) {
        requireOwner();
        return profiles.resetPrivilegedServicesProfile(packageName, virtualUserId);
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
                String code = error instanceof NativeGuestPolicyException policyError
                        ? policyError.code() : error.getClass().getSimpleName();
                return PackageServiceResult.failure(operation, code, String.valueOf(error.getMessage()));
            }
        }
    }

    private void requireOwner() {
        guard.requireOwner(Binder.getCallingUid(), Binder.getCallingPid());
        callerVerifier.requireMainProcessCaller();
    }

    private void closeInternal() {
        guard.close();
        try { clientToken.unlinkToDeath(this, 0); } catch (Exception ignored) { }
    }

}
