package com.warden.controlledsandbox.contract;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
interface IRuntimeBroker {
    Bundle prepareGuest(in Bundle request);
    Bundle launchActivity(in Bundle request);
    Bundle invokeComponent(in Bundle request);
    Bundle grantUriPermission(in Bundle request);
    Bundle revokeUriPermission(in Bundle request);
    Bundle consumeRoute(String token, String sessionId, long generation);
    Bundle activityEvent(in Bundle request);
    Bundle sessionStatus(String packageName, int virtualUserId);
    RuntimeStatusResult runtimeStatusV2(in RuntimeStatusRequest request);
    // Legacy compatibility only. New callers must use runtimeStatusV2.
    Bundle runtimeStatus();
    void stopGuest(String packageName, int virtualUserId);
}
