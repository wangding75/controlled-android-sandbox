package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.contract.PackageServiceResult;

/** Port from Runtime Broker permission coordination to the Package Service capability. */
interface RuntimePermissionGateway extends AutoCloseable {
    PackageServiceResult request(String packageName, int virtualUserId, String permission,
                                 int requestCode, String sessionId, long generation) throws Exception;
    PackageServiceResult report(String packageName, int virtualUserId, String permission,
                                int requestCode, String sessionId, long generation,
                                boolean hostGranted, String reason) throws Exception;
    @Override void close();
}
