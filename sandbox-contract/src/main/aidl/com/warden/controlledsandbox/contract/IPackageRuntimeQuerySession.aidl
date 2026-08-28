package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.PackageServiceResult;

/** Read-only Package Authority capability for trusted Runtime Broker queries. */
interface IPackageRuntimeQuerySession {
    PackageServiceResult findRecord(String packageName);
    PackageServiceResult getVirtualPackageState(String packageName, int virtualUserId);
    /** Returns all package states installed for one virtual user in one authority transaction. */
    PackageServiceResult getVirtualPackageStates(int virtualUserId);
    void close();
}
