package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.PermissionAuditSnapshot;
import com.warden.controlledsandbox.contract.RuntimePermissionRequestSnapshot;
import java.util.ArrayList;
import java.util.List;

/** Maps internal permission workflow records into validated Binder snapshots. */
final class PermissionServiceMapper {
    private PermissionServiceMapper() { }

    static RuntimePermissionRequestSnapshot toSnapshot(RuntimePermissionRequestRecord record) {
        if (record == null) return null;
        return new RuntimePermissionRequestSnapshot(record.requestId, record.packageName,
                record.virtualUserId, record.permission, record.appOpName, record.state,
                record.hostGranted, record.requestCode, record.sessionId, record.generation,
                record.createdAtMs, record.resolvedAtMs, record.reason);
    }

    static List<RuntimePermissionRequestSnapshot> toRequestSnapshots(
            List<RuntimePermissionRequestRecord> records) {
        List<RuntimePermissionRequestSnapshot> output = new ArrayList<>();
        if (records != null) for (RuntimePermissionRequestRecord record : records) output.add(toSnapshot(record));
        return output;
    }

    static List<PermissionAuditSnapshot> toAuditSnapshots(List<PermissionAuditRecord> records) {
        List<PermissionAuditSnapshot> output = new ArrayList<>();
        if (records != null) {
            for (PermissionAuditRecord record : records) {
                output.add(new PermissionAuditSnapshot(record.sequence, record.timestampMs,
                        record.packageName, record.virtualUserId, record.permission,
                        record.action, record.outcome, record.actor, record.reason,
                        record.requestId));
            }
        }
        return output;
    }
}
