package com.warden.controlledsandbox;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import com.warden.controlledsandbox.contract.IHostJobCallback;
import com.warden.controlledsandbox.contract.IPackageManagementSession;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.IRuntimePermissionSession;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationChannelSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPendingIntentSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import java.io.File;

/** Single cross-process authority for package metadata and immutable revision mutations. */
public final class PackageManagementService extends Service {
    private final Object operationLock = new Object();
    private SandboxPackageLifecycle lifecycle;
    private PackageCallerVerifier callerVerifier;
    private VirtualPackageStateBuilder packageStateBuilder;
    private HostPermissionStateResolver hostPermissions;
    private VirtualSystemServiceStore systemServices;
    private VirtualDeviceServiceStore deviceServices;
    private VirtualInteractionStore interactions;
    private VirtualNetworkServiceStore networkServices;

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

        @Override public IVirtualSystemServiceSession openVirtualSystemServiceSession(
                IBinder clientToken, String packageName, int virtualUserId, int virtualUid,
                String processName, long generation, String packageRevision) {
            if (clientToken == null || !clientToken.isBinderAlive()) {
                throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_CLIENT_TOKEN_REQUIRED");
            }
            callerVerifier.requireRuntimeBrokerCaller();
            String normalizedPackage = required(packageName, "packageName");
            synchronized (operationLock) {
                boolean installed = false;
                try {
                    for (SandboxInstance instance : lifecycle.load().instances()) {
                        if (normalizedPackage.equals(instance.packageName)
                                && virtualUserId == instance.virtualUserId) {
                            installed = true;
                            break;
                        }
                    }
                } catch (Exception error) {
                    throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_SCOPE_LOOKUP_FAILED", error);
                }
                if (!installed) throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_SCOPE_NOT_INSTALLED");
            }
            VirtualSystemServiceSession session = new VirtualSystemServiceSession(
                    Binder.getCallingUid(), clientToken,
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId), virtualUid,
                    required(processName, "processName"), generation, required(packageRevision, "packageRevision"));
            try { clientToken.linkToDeath(session, 0); }
            catch (Exception error) {
                throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_CLIENT_TOKEN_DEAD", error);
            }
            systemServices.register(session);
            return session;
        }

        @Override public boolean startVirtualJob(VirtualJobParametersSnapshot parameters,
                IHostJobCallback callback) {
            callerVerifier.requireRuntimeBrokerCaller();
            if (parameters == null || callback == null || callback.asBinder() == null
                    || !callback.asBinder().isBinderAlive()) {
                throw new IllegalArgumentException("virtual job parameters and callback are required");
            }
            return systemServices.startJob(parameters, callback, Binder.getCallingUid());
        }

        @Override public boolean stopVirtualJob(int hostJobId, int stopReason,
                int internalStopReason, String debugStopReason) {
            callerVerifier.requireRuntimeBrokerCaller();
            if (hostJobId < 0) throw new IllegalArgumentException("hostJobId must be non-negative");
            return systemServices.stopJob(hostJobId, stopReason, internalStopReason, debugStopReason);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        lifecycle = new SandboxPackageLifecycle(this);
        callerVerifier = new PackageCallerVerifier(this);
        packageStateBuilder = new VirtualPackageStateBuilder(this);
        hostPermissions = new HostPermissionStateResolver(this);
        systemServices = new VirtualSystemServiceStore(getFilesDir());
        deviceServices = new VirtualDeviceServiceStore(getFilesDir());
        interactions = new VirtualInteractionStore(getFilesDir());
        networkServices = new VirtualNetworkServiceStore(getFilesDir());
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    private String combinedMaintenanceWarning() {
        java.util.ArrayList<String> warnings = new java.util.ArrayList<>();
        addWarning(warnings, lifecycle == null ? "" : lifecycle.maintenanceWarning());
        addWarning(warnings, systemServices == null ? "" : systemServices.maintenanceWarning());
        addWarning(warnings, deviceServices == null ? "" : deviceServices.maintenanceWarning());
        addWarning(warnings, interactions == null ? "" : interactions.maintenanceWarning());
        addWarning(warnings, networkServices == null ? "" : networkServices.maintenanceWarning());
        return String.join(";", warnings);
    }

    private static void addWarning(java.util.List<String> warnings, String value) {
        if (value != null && !value.trim().isEmpty()) warnings.add(value.trim());
    }

    @Override public void onDestroy() {
        if (systemServices != null) systemServices.close();
        super.onDestroy();
    }

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
                            lifecycle.load(), combinedMaintenanceWarning())));
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
            return execute("deleteInstance", () -> {
                String normalizedPackage = required(packageName, "packageName");
                var catalog = lifecycle.deleteInstance(normalizedPackage, virtualUserId);
                VirtualSystemServiceStore.Scope scope =
                        new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
                systemServices.deleteScopeBestEffort(scope);
                deviceServices.deleteScopeBestEffort(scope);
                interactions.deleteScopeBestEffort(scope);
                networkServices.deleteScopeBestEffort(scope);
                return PackageServiceResult.successCatalog("deleteInstance",
                        PackageServiceMapper.toSnapshot(catalog, combinedMaintenanceWarning()));
            });
        }

        @Override public VirtualDeviceServiceProfileSnapshot getDeviceServiceProfile(
                String packageName, int virtualUserId) {
            requireOwner();
            synchronized (operationLock) {
                String normalizedPackage = required(packageName, "packageName");
                requirePackageInstance(normalizedPackage, virtualUserId);
                return deviceServices.getOrCreate(
                        new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId));
            }
        }

        @Override public VirtualDeviceServiceProfileSnapshot setDeviceServiceProfile(
                String packageName, int virtualUserId,
                VirtualDeviceServiceProfileSnapshot profile) {
            requireOwner();
            synchronized (operationLock) {
                String normalizedPackage = required(packageName, "packageName");
                requirePackageInstance(normalizedPackage, virtualUserId);
                VirtualSystemServiceStore.Scope scope =
                        new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
                VirtualDeviceServiceProfileSnapshot updated = deviceServices.update(scope, profile);
                systemServices.notifyDeviceProfileChanged(scope, updated.policyVersion());
                return updated;
            }
        }

        @Override public VirtualDeviceServiceProfileSnapshot resetDeviceServiceProfile(
                String packageName, int virtualUserId) {
            requireOwner();
            synchronized (operationLock) {
                String normalizedPackage = required(packageName, "packageName");
                requirePackageInstance(normalizedPackage, virtualUserId);
                VirtualSystemServiceStore.Scope scope =
                        new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
                VirtualDeviceServiceProfileSnapshot reset = deviceServices.reset(scope);
                systemServices.notifyDeviceProfileChanged(scope, reset.policyVersion());
                return reset;
            }
        }

        @Override public VirtualInteractionProfileSnapshot getInteractionProfile(
                String packageName, int virtualUserId) {
            requireOwner();
            synchronized (operationLock) {
                String normalizedPackage = required(packageName, "packageName");
                requirePackageInstance(normalizedPackage, virtualUserId);
                return interactions.getOrCreate(
                        new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId));
            }
        }

        @Override public VirtualInteractionProfileSnapshot setInteractionProfile(
                String packageName, int virtualUserId,
                VirtualInteractionProfileSnapshot profile) {
            requireOwner();
            synchronized (operationLock) {
                String normalizedPackage = required(packageName, "packageName");
                requirePackageInstance(normalizedPackage, virtualUserId);
                VirtualSystemServiceStore.Scope scope =
                        new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
                VirtualInteractionProfileSnapshot updated = interactions.update(scope, profile);
                systemServices.notifyInteractionProfileChanged(scope, updated.policyVersion());
                return updated;
            }
        }

        @Override public VirtualInteractionProfileSnapshot resetInteractionProfile(
                String packageName, int virtualUserId) {
            requireOwner();
            synchronized (operationLock) {
                String normalizedPackage = required(packageName, "packageName");
                requirePackageInstance(normalizedPackage, virtualUserId);
                VirtualSystemServiceStore.Scope scope =
                        new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
                VirtualInteractionProfileSnapshot reset = interactions.reset(scope);
                systemServices.notifyInteractionProfileChanged(scope, reset.policyVersion());
                return reset;
            }
        }

        @Override public VirtualNetworkServiceProfileSnapshot getNetworkServiceProfile(
                String packageName, int virtualUserId) {
            requireOwner();
            synchronized (operationLock) {
                String normalizedPackage = required(packageName, "packageName");
                requirePackageInstance(normalizedPackage, virtualUserId);
                return networkServices.getOrCreate(
                        new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId));
            }
        }

        @Override public VirtualNetworkServiceProfileSnapshot setNetworkServiceProfile(
                String packageName, int virtualUserId,
                VirtualNetworkServiceProfileSnapshot profile) {
            requireOwner();
            synchronized (operationLock) {
                String normalizedPackage = required(packageName, "packageName");
                requirePackageInstance(normalizedPackage, virtualUserId);
                VirtualSystemServiceStore.Scope scope =
                        new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
                VirtualNetworkServiceProfileSnapshot updated = networkServices.update(scope, profile);
                systemServices.notifyNetworkProfileChanged(scope, updated.policyVersion());
                return updated;
            }
        }

        @Override public VirtualNetworkServiceProfileSnapshot resetNetworkServiceProfile(
                String packageName, int virtualUserId) {
            requireOwner();
            synchronized (operationLock) {
                String normalizedPackage = required(packageName, "packageName");
                requirePackageInstance(normalizedPackage, virtualUserId);
                VirtualSystemServiceStore.Scope scope =
                        new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId);
                VirtualNetworkServiceProfileSnapshot reset = networkServices.reset(scope);
                systemServices.notifyNetworkProfileChanged(scope, reset.policyVersion());
                return reset;
            }
        }

        @Override public PackageServiceResult maintenanceStatus() {
            return execute("maintenanceStatus", () -> PackageServiceResult.successText(
                    "maintenanceStatus", combinedMaintenanceWarning()));
        }

        @Override public void close() {
            requireOwner();
            closeInternal();
        }

        @Override public void binderDied() { closeInternal(); }

        private void requirePackageInstance(String packageName, int virtualUserId) {
            try {
                lifecycle.packagePolicy(packageName, virtualUserId);
            } catch (Exception error) {
                throw new IllegalStateException("VIRTUAL_PACKAGE_INSTANCE_REQUIRED", error);
            }
        }

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

    private final class VirtualSystemServiceSession extends IVirtualSystemServiceSession.Stub
            implements IBinder.DeathRecipient, VirtualSystemServiceStore.Client {
        private final int ownerUid;
        private final IBinder clientToken;
        private final VirtualSystemServiceStore.Scope scope;
        private final int virtualUid;
        private final String processName;
        private final long generation;
        private final String packageRevision;
        private volatile boolean active = true;
        private volatile IVirtualSystemServiceObserver observer;

        VirtualSystemServiceSession(int ownerUid, IBinder clientToken,
                                    VirtualSystemServiceStore.Scope scope, int virtualUid,
                                    String processName, long generation, String packageRevision) {
            if (generation < 1L || virtualUid < 0) throw new IllegalArgumentException("virtual identity is invalid");
            this.ownerUid = ownerUid; this.clientToken = clientToken;
            this.scope = scope; this.virtualUid = virtualUid; this.processName = required(processName, "processName");
            this.generation = generation; this.packageRevision = required(packageRevision, "packageRevision");
        }
        @Override public byte[] getClipboard() { requireCapability(); return systemServices.clipboard(scope); }
        @Override public void setClipboard(byte[] payload) { requireCapability(); systemServices.setClipboard(scope, payload); }
        @Override public void clearClipboard() { requireCapability(); systemServices.clearClipboard(scope); }
        @Override public void registerObserver(IVirtualSystemServiceObserver value) {
            requireCapability(); observer = value;
        }
        @Override public java.util.List<VirtualAccountSnapshot> listAccounts(String type) {
            requireCapability(); return systemServices.accounts(scope, type);
        }
        @Override public boolean addAccount(String name, String type, String password) {
            requireCapability(); return systemServices.addAccount(scope, name, type, password);
        }
        @Override public boolean removeAccount(String name, String type) {
            requireCapability(); return systemServices.removeAccount(scope, name, type);
        }
        @Override public void setPassword(String name, String type, String password) {
            requireCapability(); systemServices.setPassword(scope, name, type, password);
        }
        @Override public String getPassword(String name, String type) {
            requireCapability(); return systemServices.password(scope, name, type);
        }
        @Override public void setAuthToken(String name, String type, String tokenType, String token) {
            requireCapability(); systemServices.setToken(scope, name, type, tokenType, token);
        }
        @Override public String peekAuthToken(String name, String type, String tokenType) {
            requireCapability(); return systemServices.token(scope, name, type, tokenType);
        }
        @Override public void invalidateAuthToken(String accountType, String token) {
            requireCapability(); systemServices.invalidateToken(scope, accountType, token);
        }
        @Override public VirtualPendingIntentSnapshot reservePendingIntent(
                VirtualPendingIntentSnapshot candidate, boolean noCreate,
                boolean cancelCurrent, boolean updateCurrent) {
            requireCapability(); return systemServices.reservePendingIntent(scope, processName,
                    generation, packageRevision, virtualUid, candidate, noCreate, cancelCurrent, updateCurrent);
        }
        @Override public VirtualPendingIntentSnapshot markPendingIntentSent(String tokenId) {
            requireCapability(); return systemServices.markPendingIntentSent(scope, packageRevision, tokenId);
        }
        @Override public boolean cancelPendingIntent(String tokenId) {
            requireCapability(); return systemServices.cancelPendingIntent(scope, packageRevision, tokenId);
        }
        @Override public java.util.List<VirtualPendingIntentSnapshot> listPendingIntents() {
            requireCapability(); return systemServices.pendingIntents(scope, processName, generation, packageRevision);
        }
        @Override public void scheduleAlarm(VirtualAlarmSnapshot candidate) {
            requireCapability(); systemServices.scheduleAlarm(scope, processName, generation,
                    packageRevision, candidate);
        }
        @Override public boolean cancelAlarm(String alarmId) {
            requireCapability(); return systemServices.cancelAlarm(scope, packageRevision, alarmId);
        }
        @Override public java.util.List<VirtualAlarmSnapshot> listAlarms() {
            requireCapability(); return systemServices.alarms(scope, processName, generation, packageRevision);
        }
        @Override public VirtualNotificationSnapshot reserveNotification(VirtualNotificationSnapshot candidate) {
            requireCapability(); return systemServices.reserveNotification(scope, generation, packageRevision, candidate);
        }
        @Override public void commitNotification(VirtualNotificationSnapshot value) {
            requireCapability(); systemServices.commitNotification(scope, packageRevision, value);
        }
        @Override public boolean removeNotification(int guestId, String guestTag) {
            requireCapability(); return systemServices.removeNotification(scope, packageRevision, guestId, guestTag);
        }
        @Override public java.util.List<VirtualNotificationSnapshot> listNotifications() {
            requireCapability(); return systemServices.notifications(scope, packageRevision);
        }
        @Override public void upsertNotificationChannel(VirtualNotificationChannelSnapshot value) {
            requireCapability(); systemServices.upsertNotificationChannel(scope, packageRevision, value);
        }
        @Override public boolean removeNotificationChannel(String kind, String id) {
            requireCapability(); return systemServices.removeNotificationChannel(scope, packageRevision, kind, id);
        }
        @Override public java.util.List<VirtualNotificationChannelSnapshot> listNotificationChannels() {
            requireCapability(); return systemServices.notificationChannels(scope, packageRevision);
        }
        @Override public VirtualJobSnapshot reserveJob(VirtualJobSnapshot candidate) {
            requireCapability(); return systemServices.reserveJob(scope, processName, generation,
                    packageRevision, candidate);
        }
        @Override public void commitJob(int guestId) { requireCapability(); systemServices.commitJob(scope, guestId); }
        @Override public boolean removeJob(int guestId) { requireCapability(); return systemServices.removeJob(scope, guestId); }
        @Override public java.util.List<VirtualJobSnapshot> listJobs() {
            requireCapability(); return systemServices.jobs(scope, processName, generation, packageRevision);
        }
        @Override public int ensureNamespace(String namespace, int guestId) {
            requireCapability(); return systemServices.ensureNamespace(scope, namespace, guestId);
        }
        @Override public int hostIdIfPresent(String namespace, int guestId) {
            requireCapability(); return systemServices.hostIdIfPresent(scope, namespace, guestId);
        }
        @Override public int guestIdForHost(String namespace, int hostId) {
            requireCapability(); return systemServices.guestIdForHost(scope, namespace, hostId);
        }
        @Override public int removeNamespace(String namespace, int guestId) {
            requireCapability(); return systemServices.removeNamespace(scope, namespace, guestId);
        }
        @Override public int[] listNamespaceGuestIds(String namespace) {
            requireCapability(); return systemServices.namespaceGuestIds(scope, namespace);
        }
        @Override public VirtualDeviceServiceProfileSnapshot getDeviceServiceProfile() {
            requireCapability(); return deviceServices.getOrCreate(scope);
        }
        @Override public VirtualInteractionProfileSnapshot getInteractionProfile() {
            requireCapability(); return interactions.getOrCreate(scope);
        }
        @Override public VirtualNetworkServiceProfileSnapshot getNetworkServiceProfile() {
            requireCapability(); return networkServices.getOrCreate(scope);
        }
        @Override public void close() { requireCapability(); closeInternal(); }
        @Override public void binderDied() { closeInternal(); }
        @Override public VirtualSystemServiceStore.Scope scope() { return scope; }
        @Override public String processName() { return processName; }
        @Override public long generation() { return generation; }
        @Override public IVirtualSystemServiceObserver observer() { return observer; }
        @Override public boolean active() { return active; }

        private void requireCapability() {
            if (!active || Binder.getCallingUid() != ownerUid) {
                throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_CAPABILITY_DENIED");
            }
        }
        private void closeInternal() {
            if (!active) return; active = false; observer = null;
            systemServices.unregister(this);
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
