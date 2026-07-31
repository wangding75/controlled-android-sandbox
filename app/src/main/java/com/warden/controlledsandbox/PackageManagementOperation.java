package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.PackageServiceResult;

/** Fallible package-management operation executed under the management-session lock. */
@FunctionalInterface
interface PackageManagementOperation {
    PackageServiceResult run() throws Exception;
}
