package com.warden.controlledsandbox.contract;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
interface IRuntimeBroker {
    RuntimeOperationResult executeV2(in RuntimeOperationRequest request);
    Bundle prepareGuest(in Bundle request);
    Bundle launchActivity(in Bundle request);
    Bundle invokeComponent(in Bundle request);
    Bundle grantUriPermission(in Bundle request);
    Bundle revokeUriPermission(in Bundle request);
    Bundle consumeRoute(String token, String sessionId, long generation);
    Bundle activityEvent(in Bundle request);
    ActivityTaskResult activityTaskOperation(in ActivityTaskRequest request);
    ActivityResultResult activityResultOperation(in ActivityResultRequest request);
    Bundle sessionStatus(String packageName, int virtualUserId);
    PackageServiceResult requestRuntimePermission(String sessionId, long generation,
        String permission, int requestCode);
    PackageServiceResult reportRuntimePermissionResult(String sessionId, long generation,
        String permission, int requestCode, boolean hostGranted, String reason);
    RuntimeStatusResult runtimeStatusV2(in RuntimeStatusRequest request);
    // Legacy compatibility only. New callers must use runtimeStatusV2.
    Bundle runtimeStatus();
    void stopGuest(String packageName, int virtualUserId);
}
