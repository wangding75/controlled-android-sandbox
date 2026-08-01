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

/** Single cross-process authority for package metadata and immutable revision mutations. */
public final class PackageManagementService extends Service {
    private PackageServiceDependencies dependencies;

    private final IPackageService.Stub binder = new IPackageService.Stub() {
        @Override public IPackageManagementSession openManagementSession(IBinder clientToken) {
            if (clientToken == null || !clientToken.isBinderAlive()) {
                throw new SecurityException("PACKAGE_MANAGEMENT_CLIENT_TOKEN_REQUIRED");
            }
            dependencies.callerVerifier.requireMainProcessCaller();
            int ownerUid = Binder.getCallingUid();
            int ownerPid = Binder.getCallingPid();
            PackageManagementSession session = new PackageManagementSession(dependencies, ownerUid, ownerPid, clientToken);
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
            dependencies.callerVerifier.requireRuntimeBrokerCaller();
            int ownerUid = Binder.getCallingUid();
            int ownerPid = Binder.getCallingPid();
            PackageRuntimePermissionSession session = new PackageRuntimePermissionSession(dependencies, ownerUid, ownerPid, clientToken);
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
            dependencies.callerVerifier.requireRuntimeBrokerCaller();
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
                    if (!authoritative.sha256.equals(packageRevision)) {
                        throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_REVISION_MISMATCH");
                    }
                    NativeGuestExecutionPolicy.requireRuntimeAllowed(authoritative);
                } catch (SecurityException error) {
                    throw error;
                } catch (Exception error) {
                    throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_SCOPE_LOOKUP_FAILED", error);
                }
                if (!installed) throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_SCOPE_NOT_INSTALLED");
            }
            PackageVirtualSystemServiceSession session = new PackageVirtualSystemServiceSession(
                    dependencies, Binder.getCallingUid(), clientToken,
                    new VirtualSystemServiceStore.Scope(normalizedPackage, virtualUserId), virtualUid,
                    PackageServiceDependencies.required(processName, "processName"), generation, PackageServiceDependencies.required(packageRevision, "packageRevision"));
            try { clientToken.linkToDeath(session, 0); }
            catch (Exception error) {
                throw new SecurityException("VIRTUAL_SYSTEM_SERVICE_CLIENT_TOKEN_DEAD", error);
            }
            dependencies.systemServices.register(session);
            return session;
        }

        @Override public boolean startVirtualJob(VirtualJobParametersSnapshot parameters,
                IHostJobCallback callback) {
            dependencies.callerVerifier.requireRuntimeBrokerCaller();
            if (parameters == null || callback == null || callback.asBinder() == null
                    || !callback.asBinder().isBinderAlive()) {
                throw new IllegalArgumentException("virtual job parameters and callback are required");
            }
            return dependencies.systemServices.startJob(parameters, callback, Binder.getCallingUid());
        }

        @Override public boolean stopVirtualJob(int hostJobId, int stopReason,
                int internalStopReason, String debugStopReason) {
            dependencies.callerVerifier.requireRuntimeBrokerCaller();
            if (hostJobId < 0) throw new IllegalArgumentException("hostJobId must be non-negative");
            return dependencies.systemServices.stopJob(hostJobId, stopReason, internalStopReason, debugStopReason);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        dependencies = PackageServiceDependencies.create(this, getFilesDir());
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onDestroy() {
        if (dependencies != null) dependencies.close();
        super.onDestroy();
    }







}
