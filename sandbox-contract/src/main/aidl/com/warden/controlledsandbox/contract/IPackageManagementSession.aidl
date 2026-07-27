package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.PackageServiceResult;

interface IPackageManagementSession {
    PackageServiceResult loadCatalog();
    PackageServiceResult importApk(String uri);
    PackageServiceResult importApkFile(String sourcePath);
    PackageServiceResult findRecord(String packageName);
    PackageServiceResult ensureInstance(String packageName, int virtualUserId);
    PackageServiceResult createClone(String packageName);
    PackageServiceResult updateInstanceStatus(String packageName, int virtualUserId, String status);
    PackageServiceResult deleteInstance(String packageName, int virtualUserId);
    PackageServiceResult maintenanceStatus();
    void close();
}
