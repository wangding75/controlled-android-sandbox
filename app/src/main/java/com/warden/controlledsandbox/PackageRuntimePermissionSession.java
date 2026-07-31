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
import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPolicyServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaCommunicationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutSnapshot;
import com.warden.controlledsandbox.contract.VirtualWidgetSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageEventSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingSnapshot;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import java.io.File;

import static com.warden.controlledsandbox.PackageServiceDependencies.required;

final class PackageRuntimePermissionSession extends IRuntimePermissionSession.Stub
        implements IBinder.DeathRecipient {
    private final Object operationLock;
    private final SandboxPackageLifecycle lifecycle;
    private final PackageCallerVerifier callerVerifier;
    private final VirtualPackageStateBuilder packageStateBuilder;
    private final HostPermissionStateResolver hostPermissions;

    private final RuntimePermissionSessionGuard guard;
    private final IBinder clientToken;

    PackageRuntimePermissionSession(PackageServiceDependencies dependencies,
            int ownerUid, int ownerPid, IBinder clientToken) {
        java.util.Objects.requireNonNull(dependencies, "dependencies");
        operationLock = dependencies.operationLock;
        lifecycle = dependencies.lifecycle;
        callerVerifier = dependencies.callerVerifier;
        packageStateBuilder = dependencies.packageStateBuilder;
        hostPermissions = dependencies.hostPermissions;
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

    @FunctionalInterface
    private interface Operation {
        PackageServiceResult run() throws Exception;
    }
}
