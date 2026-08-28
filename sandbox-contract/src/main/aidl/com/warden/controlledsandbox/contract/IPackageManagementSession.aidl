package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPolicyServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaCommunicationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.InstallSessionPage;
import com.warden.controlledsandbox.contract.VirtualPageRequest;

interface IPackageManagementSession {
    PackageServiceResult loadCatalog();
    PackageServiceResult importApk(String uri);
    PackageServiceResult importApkWithNativeTrust(String uri, String nativeGuestTrust);
    PackageServiceResult importApkFile(String sourcePath);
    PackageServiceResult importApkFileWithNativeTrust(String sourcePath, String nativeGuestTrust);
    PackageServiceResult importInstalledApplication(String packageName, String nativeGuestTrust);
    PackageServiceResult importInstalledApplicationAndEnsure(String requestId, String packageName,
            String nativeGuestTrust, int virtualUserId);
    PackageServiceResult getPackageOperation(String requestId);
    PackageServiceResult createInstallSession(String expectedPackageName);
    PackageServiceResult createInstallSessionWithParams(in InstallSessionParamsSnapshot params);
    PackageServiceResult getInstallSessionInfo(int sessionId);
    PackageServiceResult listInstallSessions();
    PackageServiceResult setInstallSessionProgress(int sessionId, float progress);
    PackageServiceResult retryInstallSession(int sessionId);
    PackageServiceResult addInstallArtifact(int sessionId, String sourceUri);
    PackageServiceResult commitInstallSession(int sessionId);
    PackageServiceResult abandonInstallSession(int sessionId);
    PackageServiceResult findRecord(String packageName);
    PackageServiceResult getVirtualPackageState(String packageName, int virtualUserId);
    /** Returns all package states installed for one virtual user in one authority transaction. */
    PackageServiceResult getVirtualPackageStates(int virtualUserId);
    PackageServiceResult setPermissionDecision(String packageName, int virtualUserId, String permission, String decision);
    PackageServiceResult setAppOpMode(String packageName, int virtualUserId, String opName, String mode);
    PackageServiceResult setPackageEnabledSetting(String packageName, int virtualUserId, String state);
    PackageServiceResult setComponentEnabledSetting(String packageName, int virtualUserId, String className, String state);
    PackageServiceResult resetVirtualPolicy(String packageName, int virtualUserId);
    PackageServiceResult resolveRuntimePermission(long requestId, String outcome, String reason);
    PackageServiceResult revokeRuntimePermission(String packageName, int virtualUserId, String permission, String reason);
    PackageServiceResult listPendingPermissionRequests(String packageName, int virtualUserId);
    PackageServiceResult listPermissionAudit(String packageName, int virtualUserId, int limit);
    PackageServiceResult ensureInstance(String packageName, int virtualUserId);
    PackageServiceResult createClone(String packageName);
    PackageServiceResult rollbackPackage(String packageName);
    PackageServiceResult resetIdentity(String packageName);
    PackageServiceResult lifecycleTransaction(String packageName);
    PackageServiceResult updateInstanceStatus(String packageName, int virtualUserId, String status);
    PackageServiceResult deleteInstance(String packageName, int virtualUserId);
    PackageServiceResult deleteInstanceWithOperation(String requestId, String packageName,
            int virtualUserId);
    PackageServiceResult clearInstanceData(String packageName, int virtualUserId);
    VirtualDeviceServiceProfileSnapshot getDeviceServiceProfile(String packageName, int virtualUserId);
    VirtualDeviceServiceProfileSnapshot setDeviceServiceProfile(String packageName, int virtualUserId,
            in VirtualDeviceServiceProfileSnapshot profile);
    VirtualDeviceServiceProfileSnapshot resetDeviceServiceProfile(String packageName, int virtualUserId);
    VirtualInteractionProfileSnapshot getInteractionProfile(String packageName, int virtualUserId);
    VirtualInteractionProfileSnapshot setInteractionProfile(String packageName, int virtualUserId,
            in VirtualInteractionProfileSnapshot profile);
    VirtualInteractionProfileSnapshot resetInteractionProfile(String packageName, int virtualUserId);
    VirtualNetworkServiceProfileSnapshot getNetworkServiceProfile(String packageName, int virtualUserId);
    VirtualNetworkServiceProfileSnapshot setNetworkServiceProfile(String packageName, int virtualUserId,
            in VirtualNetworkServiceProfileSnapshot profile);
    VirtualNetworkServiceProfileSnapshot resetNetworkServiceProfile(String packageName, int virtualUserId);
    ApplicationEnvironmentProfileSnapshot getApplicationEnvironmentProfile(String packageName, int virtualUserId);
    ApplicationEnvironmentProfileSnapshot setApplicationEnvironmentProfile(String packageName, int virtualUserId,
            in ApplicationEnvironmentProfileSnapshot profile);
    ApplicationEnvironmentProfileSnapshot resetApplicationEnvironmentProfile(String packageName, int virtualUserId);
    VirtualCompatibilityProfileSnapshot getCompatibilityProfile(String packageName, int virtualUserId);
    VirtualCompatibilityProfileSnapshot setCompatibilityProfile(String packageName, int virtualUserId,
            in VirtualCompatibilityProfileSnapshot profile);
    VirtualCompatibilityProfileSnapshot resetCompatibilityProfile(String packageName, int virtualUserId);
    VirtualPolicyServicesProfileSnapshot getPolicyServicesProfile(String packageName, int virtualUserId);
    VirtualPolicyServicesProfileSnapshot setPolicyServicesProfile(String packageName, int virtualUserId,
            in VirtualPolicyServicesProfileSnapshot profile);
    VirtualPolicyServicesProfileSnapshot resetPolicyServicesProfile(String packageName, int virtualUserId);
    VirtualMediaCommunicationProfileSnapshot getMediaCommunicationProfile(String packageName, int virtualUserId);
    VirtualMediaCommunicationProfileSnapshot setMediaCommunicationProfile(String packageName, int virtualUserId,
            in VirtualMediaCommunicationProfileSnapshot profile);
    VirtualMediaCommunicationProfileSnapshot resetMediaCommunicationProfile(String packageName, int virtualUserId);
    VirtualPeripheralServicesProfileSnapshot getPeripheralServicesProfile(String packageName, int virtualUserId);
    VirtualPeripheralServicesProfileSnapshot setPeripheralServicesProfile(String packageName, int virtualUserId,
            in VirtualPeripheralServicesProfileSnapshot profile);
    VirtualPeripheralServicesProfileSnapshot resetPeripheralServicesProfile(String packageName, int virtualUserId);
    VirtualPrivilegedServicesProfileSnapshot getPrivilegedServicesProfile(String packageName, int virtualUserId);
    VirtualPrivilegedServicesProfileSnapshot setPrivilegedServicesProfile(String packageName, int virtualUserId,
            in VirtualPrivilegedServicesProfileSnapshot profile);
    VirtualPrivilegedServicesProfileSnapshot resetPrivilegedServicesProfile(String packageName, int virtualUserId);
    PackageServiceResult maintenanceStatus();
    void close();
    InstallSessionPage listInstallSessionsPage(in VirtualPageRequest request);
}
