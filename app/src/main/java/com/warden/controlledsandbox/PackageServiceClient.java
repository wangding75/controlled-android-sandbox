package com.warden.controlledsandbox;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IPackageManagementSession;
import com.warden.controlledsandbox.contract.IPackageService;
import com.warden.controlledsandbox.contract.PackageCatalogSnapshot;
import com.warden.controlledsandbox.contract.PackageRecordSnapshot;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.InstallSessionInfoSnapshot;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.RuntimePermissionRequestSnapshot;
import com.warden.controlledsandbox.contract.PermissionAuditSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPolicyServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaCommunicationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.io.File;
import java.util.concurrent.TimeUnit;

/** Main-process client for the Binder-owned package authority. */
final class PackageServiceClient implements AutoCloseable {
    private final Context context;
    private final Binder clientToken = new Binder();
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile IPackageManagementSession session;
    private volatile Exception connectionFailure;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                IPackageService root = IPackageService.Stub.asInterface(service);
                session = root == null ? null : root.openManagementSession(clientToken);
            } catch (Exception error) {
                connectionFailure = error;
                session = null;
            } finally {
                connected.countDown();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) { session = null; }
    };

    PackageServiceClient(Context context) {
        this.context = context.getApplicationContext();
        boolean bound = this.context.bindService(
                new Intent(this.context, PackageManagementService.class),
                connection, Context.BIND_AUTO_CREATE);
        if (!bound) connected.countDown();
    }

    SandboxCatalogState load() throws Exception {
        PackageCatalogSnapshot snapshot = requireSuccess(requireSession().loadCatalog()).catalog();
        if (snapshot == null) throw new IllegalStateException("Package service returned no catalog");
        return PackageServiceMapper.fromSnapshot(snapshot);
    }

    SandboxRecord importApk(Uri uri) throws Exception {
        return record(requireSession().importApk(uri == null ? "" : uri.toString()));
    }

    SandboxRecord importApkFile(File source) throws Exception {
        return record(requireSession().importApkFile(source == null ? "" : source.getAbsolutePath()));
    }

    int createInstallSession(String expectedPackageName) throws Exception {
        return requireSuccess(requireSession().createInstallSession(
                expectedPackageName == null ? "" : expectedPackageName)).intValue();
    }

    InstallSessionInfoSnapshot createInstallSession(InstallSessionParamsSnapshot params)
            throws Exception {
        PackageServiceResult result = requireSuccess(
                requireSession().createInstallSessionWithParams(params));
        if (result.installSession() == null) {
            throw new IllegalStateException("Package service returned no install session");
        }
        return result.installSession();
    }

    InstallSessionInfoSnapshot installSessionInfo(int sessionId) throws Exception {
        PackageServiceResult result = requireSuccess(requireSession().getInstallSessionInfo(sessionId));
        if (result.installSession() == null) {
            throw new IllegalStateException("Package service returned no install session");
        }
        return result.installSession();
    }

    List<InstallSessionInfoSnapshot> installSessions() throws Exception {
        return requireSuccess(requireSession().listInstallSessions()).installSessions();
    }

    InstallSessionInfoSnapshot setInstallSessionProgress(int sessionId, float progress)
            throws Exception {
        PackageServiceResult result = requireSuccess(
                requireSession().setInstallSessionProgress(sessionId, progress));
        if (result.installSession() == null) {
            throw new IllegalStateException("Package service returned no install session");
        }
        return result.installSession();
    }

    InstallSessionInfoSnapshot retryInstallSession(int sessionId) throws Exception {
        PackageServiceResult result = requireSuccess(requireSession().retryInstallSession(sessionId));
        if (result.installSession() == null) {
            throw new IllegalStateException("Package service returned no install session");
        }
        return result.installSession();
    }

    String addInstallArtifact(int sessionId, Uri source) throws Exception {
        return requireSuccess(requireSession().addInstallArtifact(sessionId,
                source == null ? "" : source.toString())).textValue();
    }

    SandboxRecord commitInstallSession(int sessionId) throws Exception {
        return record(requireSession().commitInstallSession(sessionId));
    }

    void abandonInstallSession(int sessionId) throws Exception {
        requireSuccess(requireSession().abandonInstallSession(sessionId));
    }

    SandboxRecord findRecord(String packageName) throws Exception {
        PackageServiceResult result = requireSuccess(requireSession().findRecord(packageName));
        PackageRecordSnapshot record = result.record();
        return record == null ? null : PackageServiceMapper.fromSnapshot(record);
    }

    VirtualPackageStateSnapshot virtualPackageState(String packageName, int virtualUserId)
            throws Exception {
        return packageState(requireSession().getVirtualPackageState(packageName, virtualUserId));
    }

    VirtualPackageStateSnapshot setPermissionDecision(String packageName, int virtualUserId,
                                                        String permission, String decision)
            throws Exception {
        return packageState(requireSession().setPermissionDecision(
                packageName, virtualUserId, permission, decision));
    }

    VirtualPackageStateSnapshot setAppOpMode(String packageName, int virtualUserId,
                                              String opName, String mode) throws Exception {
        return packageState(requireSession().setAppOpMode(packageName, virtualUserId, opName, mode));
    }

    VirtualPackageStateSnapshot setPackageEnabledSetting(String packageName, int virtualUserId,
                                                          String state) throws Exception {
        return packageState(requireSession().setPackageEnabledSetting(
                packageName, virtualUserId, state));
    }

    VirtualPackageStateSnapshot setComponentEnabledSetting(String packageName, int virtualUserId,
                                                            String className, String state) throws Exception {
        return packageState(requireSession().setComponentEnabledSetting(
                packageName, virtualUserId, className, state));
    }

    VirtualPackageStateSnapshot resetVirtualPolicy(String packageName, int virtualUserId)
            throws Exception {
        return packageState(requireSession().resetVirtualPolicy(packageName, virtualUserId));
    }

    RuntimePermissionRequestSnapshot resolveRuntimePermission(long requestId, String outcome,
                                                                 String reason) throws Exception {
        PackageServiceResult result = requireSuccess(requireSession().resolveRuntimePermission(
                requestId, outcome, reason));
        RuntimePermissionRequestSnapshot request = result.permissionRequest();
        if (request == null) throw new IllegalStateException("Package service returned no permission request");
        return request;
    }

    VirtualPackageStateSnapshot revokeRuntimePermission(String packageName, int virtualUserId,
                                                         String permission, String reason)
            throws Exception {
        return packageState(requireSession().revokeRuntimePermission(
                packageName, virtualUserId, permission, reason));
    }

    List<RuntimePermissionRequestSnapshot> pendingPermissionRequests(
            String packageName, int virtualUserId) throws Exception {
        return requireSuccess(requireSession().listPendingPermissionRequests(
                packageName, virtualUserId)).permissionRequests();
    }

    List<PermissionAuditSnapshot> permissionAudit(String packageName, int virtualUserId,
                                                   int limit) throws Exception {
        return requireSuccess(requireSession().listPermissionAudit(
                packageName, virtualUserId, limit)).permissionAudit();
    }

    void ensureInstance(String packageName, int virtualUserId) throws Exception {
        requireSuccess(requireSession().ensureInstance(packageName, virtualUserId));
    }

    int createClone(String packageName) throws Exception {
        return requireSuccess(requireSession().createClone(packageName)).intValue();
    }

    void updateInstanceStatus(String packageName, int virtualUserId, String status) throws Exception {
        requireSuccess(requireSession().updateInstanceStatus(packageName, virtualUserId, status));
    }

    SandboxCatalogState deleteInstance(String packageName, int virtualUserId) throws Exception {
        PackageCatalogSnapshot snapshot = requireSuccess(
                requireSession().deleteInstance(packageName, virtualUserId)).catalog();
        if (snapshot == null) throw new IllegalStateException("Package service returned no catalog");
        return PackageServiceMapper.fromSnapshot(snapshot);
    }

    VirtualDeviceServiceProfileSnapshot deviceServiceProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().getDeviceServiceProfile(packageName, virtualUserId);
    }

    VirtualDeviceServiceProfileSnapshot setDeviceServiceProfile(
            String packageName, int virtualUserId,
            VirtualDeviceServiceProfileSnapshot profile) throws Exception {
        return requireSession().setDeviceServiceProfile(packageName, virtualUserId, profile);
    }

    VirtualDeviceServiceProfileSnapshot resetDeviceServiceProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().resetDeviceServiceProfile(packageName, virtualUserId);
    }

    VirtualInteractionProfileSnapshot interactionProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().getInteractionProfile(packageName, virtualUserId);
    }

    VirtualInteractionProfileSnapshot setInteractionProfile(
            String packageName, int virtualUserId,
            VirtualInteractionProfileSnapshot profile) throws Exception {
        return requireSession().setInteractionProfile(packageName, virtualUserId, profile);
    }

    VirtualInteractionProfileSnapshot resetInteractionProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().resetInteractionProfile(packageName, virtualUserId);
    }

    VirtualNetworkServiceProfileSnapshot networkServiceProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().getNetworkServiceProfile(packageName, virtualUserId);
    }

    VirtualNetworkServiceProfileSnapshot setNetworkServiceProfile(
            String packageName, int virtualUserId,
            VirtualNetworkServiceProfileSnapshot profile) throws Exception {
        return requireSession().setNetworkServiceProfile(packageName, virtualUserId, profile);
    }

    VirtualNetworkServiceProfileSnapshot resetNetworkServiceProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().resetNetworkServiceProfile(packageName, virtualUserId);
    }

    ApplicationEnvironmentProfileSnapshot applicationEnvironmentProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().getApplicationEnvironmentProfile(packageName, virtualUserId);
    }

    ApplicationEnvironmentProfileSnapshot setApplicationEnvironmentProfile(
            String packageName, int virtualUserId,
            ApplicationEnvironmentProfileSnapshot profile) throws Exception {
        return requireSession().setApplicationEnvironmentProfile(packageName, virtualUserId, profile);
    }

    ApplicationEnvironmentProfileSnapshot resetApplicationEnvironmentProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().resetApplicationEnvironmentProfile(packageName, virtualUserId);
    }

    VirtualCompatibilityProfileSnapshot compatibilityProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().getCompatibilityProfile(packageName, virtualUserId);
    }

    VirtualCompatibilityProfileSnapshot setCompatibilityProfile(
            String packageName, int virtualUserId,
            VirtualCompatibilityProfileSnapshot profile) throws Exception {
        return requireSession().setCompatibilityProfile(packageName, virtualUserId, profile);
    }

    VirtualCompatibilityProfileSnapshot resetCompatibilityProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().resetCompatibilityProfile(packageName, virtualUserId);
    }

    VirtualPolicyServicesProfileSnapshot policyServicesProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().getPolicyServicesProfile(packageName, virtualUserId);
    }

    VirtualPolicyServicesProfileSnapshot setPolicyServicesProfile(
            String packageName, int virtualUserId,
            VirtualPolicyServicesProfileSnapshot profile) throws Exception {
        return requireSession().setPolicyServicesProfile(packageName, virtualUserId, profile);
    }

    VirtualPolicyServicesProfileSnapshot resetPolicyServicesProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().resetPolicyServicesProfile(packageName, virtualUserId);
    }

    VirtualMediaCommunicationProfileSnapshot mediaCommunicationProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().getMediaCommunicationProfile(packageName, virtualUserId);
    }

    VirtualMediaCommunicationProfileSnapshot setMediaCommunicationProfile(
            String packageName, int virtualUserId,
            VirtualMediaCommunicationProfileSnapshot profile) throws Exception {
        return requireSession().setMediaCommunicationProfile(packageName, virtualUserId, profile);
    }

    VirtualMediaCommunicationProfileSnapshot resetMediaCommunicationProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().resetMediaCommunicationProfile(packageName, virtualUserId);
    }


    VirtualPeripheralServicesProfileSnapshot peripheralServicesProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().getPeripheralServicesProfile(packageName, virtualUserId);
    }

    VirtualPeripheralServicesProfileSnapshot setPeripheralServicesProfile(
            String packageName, int virtualUserId,
            VirtualPeripheralServicesProfileSnapshot profile) throws Exception {
        return requireSession().setPeripheralServicesProfile(packageName, virtualUserId, profile);
    }

    VirtualPeripheralServicesProfileSnapshot resetPeripheralServicesProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().resetPeripheralServicesProfile(packageName, virtualUserId);
    }

    String maintenanceWarning() throws Exception {
        return requireSuccess(requireSession().maintenanceStatus()).textValue();
    }

    private VirtualPackageStateSnapshot packageState(PackageServiceResult raw) {
        VirtualPackageStateSnapshot state = requireSuccess(raw).packageState();
        if (state == null) throw new IllegalStateException("Package service returned no virtual package state");
        return state;
    }

    private SandboxRecord record(PackageServiceResult raw) throws Exception {
        PackageRecordSnapshot record = requireSuccess(raw).record();
        if (record == null) throw new IllegalStateException("Package service returned no package record");
        return PackageServiceMapper.fromSnapshot(record);
    }

    VirtualPrivilegedServicesProfileSnapshot privilegedServicesProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().getPrivilegedServicesProfile(packageName, virtualUserId);
    }

    VirtualPrivilegedServicesProfileSnapshot setPrivilegedServicesProfile(
            String packageName, int virtualUserId,
            VirtualPrivilegedServicesProfileSnapshot profile) throws Exception {
        return requireSession().setPrivilegedServicesProfile(packageName, virtualUserId, profile);
    }

    VirtualPrivilegedServicesProfileSnapshot resetPrivilegedServicesProfile(
            String packageName, int virtualUserId) throws Exception {
        return requireSession().resetPrivilegedServicesProfile(packageName, virtualUserId);
    }

    private IPackageManagementSession requireSession() throws Exception {
        if (!connected.await(10, TimeUnit.SECONDS) || session == null) {
            Exception failure = connectionFailure;
            throw new IllegalStateException("Package management service is unavailable"
                    + (failure == null ? "" : ": " + failure.getMessage()), failure);
        }
        return session;
    }

    private static PackageServiceResult requireSuccess(PackageServiceResult result) {
        if (result == null) throw new IllegalStateException("Package service returned no result");
        if (!result.successful()) {
            throw new IllegalStateException(result.errorCode() + ": " + result.errorMessage());
        }
        return result;
    }

    @Override public void close() {
        IPackageManagementSession current = session;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) { }
        }
        try { context.unbindService(connection); } catch (Exception ignored) { }
        session = null;
    }
}
