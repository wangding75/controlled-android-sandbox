package com.warden.controlledsandbox.runtime.status;

import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.contract.RuntimeStatusSnapshot;
import com.warden.controlledsandbox.contract.SandboxError;

/** Explicit compatibility boundary for callers that still consume Bundle runtime status. */
public final class RuntimeStatusLegacyAdapter {
    public static Bundle toBundle(RuntimeStatusResult result) {
        if (result == null) throw new IllegalArgumentException("result is required");
        Bundle out = new Bundle();
        out.putInt(RuntimeKeys.PROTOCOL, result.protocolVersion());
        out.putString("requestId", result.requestId());
        out.putString(RuntimeKeys.STATUS, result.status());
        if (!result.successful()) {
            SandboxError error = result.error();
            out.putString(RuntimeKeys.ERROR_TYPE, error == null ? "UNKNOWN" : error.code());
            out.putString(RuntimeKeys.ERROR_MESSAGE, error == null ? "" : error.message());
            out.putBoolean("retryable", error != null && error.retryable());
            return out;
        }
        out.putString("capability", result.capability());
        out.putString("warning", result.warning());
        RuntimeStatusSnapshot snapshot = result.snapshot();
        out.putInt("slotCapacity", snapshot.slotCapacity());
        out.putInt("slotUsed", snapshot.slotUsed());
        out.putInt("sessionCount", snapshot.sessionCount());
        out.putInt("pendingRoutes", snapshot.pendingRoutes());
        out.putInt(RuntimeKeys.TASK_COUNT, snapshot.taskCount());
        out.putInt(RuntimeKeys.ACTIVITY_COUNT, snapshot.activityCount());
        out.putInt(RuntimeKeys.SERVICE_RECORD_COUNT, snapshot.serviceRecordCount());
        out.putInt("uriGrantCount", snapshot.uriGrantCount());
        out.putInt("providerCursorAccessCount", snapshot.providerCursorAccessCount());
        out.putInt("providerFileLeaseCount", snapshot.providerFileLeaseCount());
        out.putInt("providerObserverCount", snapshot.providerObserverCount());
        out.putInt("providerAuthorityCount", snapshot.providerAuthorityCount());
        out.putInt("providerResourceCount", snapshot.providerResourceCount());
        out.putInt("providerAuditRetainedCount", snapshot.providerAuditRetainedCount());
        out.putLong("providerAuditSuccessCount", snapshot.providerAuditSuccessCount());
        out.putLong("providerAuditFailureCount", snapshot.providerAuditFailureCount());
        out.putInt("dynamicReceiverCount", snapshot.dynamicReceiverCount());
        out.putInt("dynamicReceiverActionSubscriptionCount",
                snapshot.dynamicReceiverActionSubscriptionCount());
        out.putInt("manifestReceiverPackageCount", snapshot.manifestReceiverPackageCount());
        out.putInt("manifestReceiverCount", snapshot.manifestReceiverCount());
        out.putInt("manifestReceiverBindingCount", snapshot.manifestReceiverBindingCount());
        out.putInt("manifestReceiverActionIndexKeyCount",
                snapshot.manifestReceiverActionIndexKeyCount());
        out.putInt("manifestReceiverActionIndexEntryCount",
                snapshot.manifestReceiverActionIndexEntryCount());
        out.putInt("manifestReceiverStartupTemplateCount",
                snapshot.manifestReceiverStartupTemplateCount());
        out.putInt("orderedReceiverPendingCount", snapshot.orderedReceiverPendingCount());
        out.putInt("receiverResourceCount", snapshot.receiverResourceCount());
        return out;
    }

    private RuntimeStatusLegacyAdapter() { }
}
