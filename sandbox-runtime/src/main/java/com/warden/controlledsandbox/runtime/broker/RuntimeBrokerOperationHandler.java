package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;

/** Internal broker operation boundary; Bundle payloads never cross the public AIDL surface. */
interface RuntimeBrokerOperationHandler {
    Bundle prepareGuest(Bundle request);
    Bundle launchActivity(Bundle request);
    Bundle invokeComponent(Bundle request);
    Bundle grantUriPermission(Bundle request);
    Bundle revokeUriPermission(Bundle request);
    Bundle checkUriPermission(Bundle request);
    Bundle openPackageResources(Bundle request);
    Bundle consumeRoute(String token, String sessionId, long generation);
    Bundle activityEvent(Bundle request);
    Bundle storageOperation(Bundle request, String sessionId, long generation);
    Bundle sessionStatus(String packageName, int virtualUserId);
    Bundle runtimeStatus();
}
