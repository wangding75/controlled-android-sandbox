package com.warden.controlledsandbox.contract;
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
    ActivityTaskResult activityTaskOperation(in ActivityTaskRequest request);
    ActivityResultResult activityResultOperation(in ActivityResultRequest request);
    PackageServiceResult requestRuntimePermission(String sessionId, long generation,
        String permission, int requestCode);
    PackageServiceResult reportRuntimePermissionResult(String sessionId, long generation,
        String permission, int requestCode, boolean hostGranted, String reason);
    RuntimeStatusResult runtimeStatusV2(in RuntimeStatusRequest request);
    // Authoritative virtual UID lookup used when constructing PackageManager projections.
    // The mapping is owned by Runtime Broker and must not be recreated by a caller.
    int virtualUidFor(String packageName, int virtualUserId);
    // Batch form used while constructing a PackageManager universe; preserves Broker ownership
    // without one Binder transaction per unrelated installed package.
    int[] virtualUidsFor(in String[] packageNames, int virtualUserId);
    // Legacy compatibility only. New callers must use runtimeStatusV2.
    void stopGuest(String packageName, int virtualUserId);
}
