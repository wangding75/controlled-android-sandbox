package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.PackageServiceResult;

interface IPackageManagementSession {
    PackageServiceResult loadCatalog();
    PackageServiceResult importApk(String uri);
    PackageServiceResult importApkFile(String sourcePath);
    PackageServiceResult createInstallSession(String expectedPackageName);
    PackageServiceResult addInstallArtifact(int sessionId, String sourceUri);
    PackageServiceResult commitInstallSession(int sessionId);
    PackageServiceResult abandonInstallSession(int sessionId);
    PackageServiceResult findRecord(String packageName);
    PackageServiceResult getVirtualPackageState(String packageName, int virtualUserId);
    PackageServiceResult setPermissionDecision(String packageName, int virtualUserId, String permission, String decision);
    PackageServiceResult setAppOpMode(String packageName, int virtualUserId, String opName, String mode);
    PackageServiceResult resetVirtualPolicy(String packageName, int virtualUserId);
    PackageServiceResult ensureInstance(String packageName, int virtualUserId);
    PackageServiceResult createClone(String packageName);
    PackageServiceResult updateInstanceStatus(String packageName, int virtualUserId, String status);
    PackageServiceResult deleteInstance(String packageName, int virtualUserId);
    PackageServiceResult maintenanceStatus();
    void close();
}
