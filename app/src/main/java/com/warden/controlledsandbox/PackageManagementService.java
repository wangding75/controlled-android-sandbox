package com.warden.controlledsandbox;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import com.warden.controlledsandbox.contract.IPackageManagementSession;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import java.io.File;

/** Single cross-process authority for package metadata and immutable revision mutations. */
public final class PackageManagementService extends Service {
    private final Object operationLock = new Object();
    private SandboxPackageLifecycle lifecycle;
    private PackageCallerVerifier callerVerifier;
    private VirtualPackageStateBuilder packageStateBuilder;

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
    };

    @Override public void onCreate() {
        super.onCreate();
        lifecycle = new SandboxPackageLifecycle(this);
        callerVerifier = new PackageCallerVerifier(this);
        packageStateBuilder = new VirtualPackageStateBuilder();
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

        @Override public PackageServiceResult resetVirtualPolicy(String packageName,
                                                                  int virtualUserId) {
            return execute("resetVirtualPolicy", () -> packageStateResult("resetVirtualPolicy",
                    lifecycle.resetPolicy(required(packageName, "packageName"), virtualUserId),
                    virtualUserId));
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
                    packageStateBuilder.build(view.record, virtualUserId, view.policy));
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

    private interface Operation { PackageServiceResult run() throws Exception; }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
