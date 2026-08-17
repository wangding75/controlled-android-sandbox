package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.PackageServiceResult;

/** Read-only Package Authority capability for trusted Runtime Broker queries. */
interface IPackageRuntimeQuerySession {
    PackageServiceResult findRecord(String packageName);
    PackageServiceResult getVirtualPackageState(String packageName, int virtualUserId);
    void close();
}
