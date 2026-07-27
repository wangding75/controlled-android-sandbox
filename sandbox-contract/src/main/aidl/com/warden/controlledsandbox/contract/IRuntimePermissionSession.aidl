package com.warden.controlledsandbox.contract;

import com.warden.controlledsandbox.contract.PackageServiceResult;

interface IRuntimePermissionSession {
    PackageServiceResult requestRuntimePermission(String packageName, int virtualUserId,
        String permission, int requestCode, String sessionId, long generation);
    PackageServiceResult reportRuntimePermissionResult(String packageName, int virtualUserId,
        String permission, int requestCode, String sessionId, long generation,
        boolean hostGranted, String reason);
    void close();
}
