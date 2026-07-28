package com.warden.controlledsandbox;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import com.warden.controlledsandbox.contract.IPackageManagementSession;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.IRuntimePermissionSession;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import java.io.File;

/** Single cross-process authority for package metadata and immutable revision mutations. */
public final class PackageManagementService extends Service {
    private final Object operationLock = new Object();
    private SandboxPackageLifecycle lifecycle;
    private PackageCallerVerifier callerVerifier;
    private VirtualPackageStateBuilder packageStateBuilder;
    private HostPermissionStateResolver hostPermissions;

    private final IPackageService.Stub binder = new IPackageService.Stub() {
        @Override public IPackageManagementSession openManagementSession(IBinder clientToken) {
            if (clientToken == null || !clientToken.isBinderAlive()) {
                throw new SecurityException("PACKAGE_MANAGEMENT_CLIENT_TOKEN_REQUIRED");
            }
            callerVerifier.requireMainProcessCaller();
            int ownerUid = Binder.getCallingUid();
            int ownerPid = Binder.getCallingPid();
            ManagementSession session = new ManagementSession(ownerUid, ownerPid, clientToken);
            try {
                clientToken.linkToDeath(session, 0);
            } catch (Exception error) {
                throw new SecurityException("PACKAGE_MANAGEMENT_CLIENT_TOKEN_DEAD", error);
            }
            return session;
        }

        @Override public IRuntimePermissionSession openRuntimePermissionSession(IBinder clientToken) {
            if (clientToken == null || !clientToken.isBinderAlive()) {
                throw new SecurityException("RUNTIME_PERMISSION_CLIENT_TOKEN_REQUIRED");
            }
            callerVerifier.requireRuntimeBrokerCaller();
            int ownerUid = Binder.getCallingUid();
            int ownerPid = Binder.getCallingPid();
            RuntimePermissionSession session = new RuntimePermissionSession(ownerUid, ownerPid, clientToken);
            try {
                clientToken.linkToDeath(session, 0);
            } catch (Exception error) {
                throw new SecurityException("RUNTIME_PERMISSION_CLIENT_TOKEN_DEAD", error);
            }
            return session;
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        lifecycle = new SandboxPackageLifecycle(this);
        callerVerifier = new PackageCallerVerifier(this);
        packageStateBuilder = new VirtualPackageStateBuilder(this);
        hostPermissions = new HostPermissionStateResolver(this);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    private final class ManagementSession extends IPackageManagementSession.Stub
            implements IBinder.DeathRecipient {
        private final ManagementSessionGuard guard;
        private final IBinder clientToken;

        ManagementSession(int ownerUid, int ownerPid, IBinder clientToken) {
            guard = new ManagementSessionGuard(ownerUid, ownerPid);
            this.clientToken = clientToken;
        }

        @Override public PackageServiceResult loadCatalog() {
            return execute("loadCatalog", () -> PackageServiceResult.successCatalog(
                    "loadCatalog", PackageServiceMapper.toSnapshot(
                            lifecycle.load(), lifecycle.maintenanceWarning())));
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

        @Override public PackageServiceResult ensureInstance(String packageName, int virtualUserId) {
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

        @Override public PackageServiceResult deleteInstance(String packageName, int virtualUserId) {
            return execute("deleteInstance", () -> PackageServiceResult.successCatalog(
                    "deleteInstance", PackageServiceMapper.toSnapshot(
                            lifecycle.deleteInstance(required(packageName, "packageName"), virtualUserId),
                            lifecycle.maintenanceWarning())));
        }

        @Override public PackageServiceResult maintenanceStatus() {
            return execute("maintenanceStatus", () -> PackageServiceResult.successText(
                    "maintenanceStatus", lifecycle.maintenanceWarning()));
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

        private PackageServiceResult execute(String operation, Operation action) {
            requireOwner();
            synchronized (operationLock) {
                try {
                    return action.run();
                } catch (Throwable error) {
                    return PackageServiceResult.failure(operation,
                            error.getClass().getSimpleName(), String.valueOf(error.getMessage()));
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

    private final class RuntimePermissionSession extends IRuntimePermissionSession.Stub
            implements IBinder.DeathRecipient {
        private final RuntimePermissionSessionGuard guard;
        private final IBinder clientToken;

        RuntimePermissionSession(int ownerUid, int ownerPid, IBinder clientToken) {
            guard = new RuntimePermissionSessionGuard(ownerUid, ownerPid);
            this.clientToken = clientToken;
        }

        @Override public PackageServiceResult requestRuntimePermission(String packageName,
                int virtualUserId, String permission, int requestCode, String sessionId,
                long generation) {
            return executeRuntime("requestRuntimePermission", () -> {
                String normalizedPackage = required(packageName, "packageName");
                String normalizedPermission = required(permission, "permission");
                SandboxPackagePolicyView view = lifecycle.packagePolicy(
                        normalizedPackage, virtualUserId);
                if (!packageStateBuilder.declaresPermission(view.record, normalizedPermission)) {
                    throw new SecurityException("GUEST_PERMISSION_NOT_DECLARED");
                }
                PermissionCapabilityRegistry.Capability capability =
                        PermissionCapabilityRegistry.resolve(normalizedPermission);
                HostPermissionStateResolver.HostState host = hostPermissions.resolve(normalizedPermission);
                SandboxCatalogState.PermissionRequestResult result = lifecycle.requestRuntimePermission(
                        normalizedPackage, virtualUserId, normalizedPermission,
                        capability.appOpName, host.grantedToHost, requestCode,
                        required(sessionId, "sessionId"), generation, "RUNTIME_BROKER");
                SandboxPackagePolicyView updated = new SandboxPackagePolicyView(
                        result.state.findRecord(normalizedPackage),
                        result.state.policy(normalizedPackage, virtualUserId), result.state);
                return PackageServiceResult.successPermissionRequest("requestRuntimePermission",
                        PermissionServiceMapper.toSnapshot(result.request),
                        packageStateBuilder.build(updated.record, virtualUserId,
                                updated.policy, updated.catalog));
            });
        }

        @Override public PackageServiceResult reportRuntimePermissionResult(String packageName,
                int virtualUserId, String permission, int requestCode, String sessionId,
                long generation, boolean hostGranted, String reason) {
            return executeRuntime("reportRuntimePermissionResult", () -> {
                String normalizedPackage = required(packageName, "packageName");
                String normalizedPermission = required(permission, "permission");
                PermissionCapabilityRegistry.Capability capability =
                        PermissionCapabilityRegistry.resolve(normalizedPermission);
                HostPermissionStateResolver.HostState actualHost = hostPermissions.resolve(normalizedPermission);
                if (hostGranted != actualHost.grantedToHost) {
                    throw new SecurityException("RUNTIME_PERMISSION_HOST_RESULT_MISMATCH");
                }
                String normalizedSession = required(sessionId, "sessionId");
                RuntimePermissionRequestRecord pending = lifecycle.pendingPermissionRequest(
                        normalizedPackage, virtualUserId, normalizedPermission, requestCode,
                        normalizedSession, generation);
                if (pending == null) {
                    throw new SecurityException("RUNTIME_PERMISSION_PENDING_REQUEST_REQUIRED");
                }
                String outcome = actualHost.grantedToHost
                        ? RuntimePermissionRequestRecord.GRANTED
                        : RuntimePermissionRequestRecord.DENIED;
                SandboxCatalogState.PermissionRequestResult resolved = lifecycle.resolveRuntimePermission(
                        pending.requestId, outcome, actualHost.grantedToHost, reason,
                        "ANDROID_PERMISSION_RESULT");
                SandboxPackagePolicyView updated = new SandboxPackagePolicyView(
                        resolved.state.findRecord(normalizedPackage),
                        resolved.state.policy(normalizedPackage, virtualUserId), resolved.state);
                return PackageServiceResult.successPermissionRequest(
                        "reportRuntimePermissionResult",
                        PermissionServiceMapper.toSnapshot(resolved.request),
                        packageStateBuilder.build(updated.record, virtualUserId,
                                updated.policy, updated.catalog));
            });
        }

        @Override public void close() { requireRuntimeOwner(); closeInternal(); }
        @Override public void binderDied() { closeInternal(); }

        private PackageServiceResult executeRuntime(String operation, Operation action) {
            requireRuntimeOwner();
            synchronized (operationLock) {
                try { return action.run(); }
                catch (Throwable error) {
                    return PackageServiceResult.failure(operation, error.getClass().getSimpleName(),
                            String.valueOf(error.getMessage()));
                }
            }
        }
        private void requireRuntimeOwner() {
            guard.requireOwner(Binder.getCallingUid(), Binder.getCallingPid());
            callerVerifier.requireRuntimeBrokerCaller();
        }
        private void closeInternal() {
            guard.close();
            try { clientToken.unlinkToDeath(this, 0); } catch (Exception ignored) { }
        }
    }

    private interface Operation { PackageServiceResult run() throws Exception; }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
