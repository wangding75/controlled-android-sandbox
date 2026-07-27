package com.warden.controlledsandbox;

import org.json.JSONObject;

/** Immutable bounded audit entry for permission request, decision and revocation events. */
final class PermissionAuditRecord {
    final long sequence;
    final long timestampMs;
    final String packageName;
    final int virtualUserId;
    final String permission;
    final String action;
    final String outcome;
    final String actor;
    final String reason;
    final long requestId;

    PermissionAuditRecord(long sequence, long timestampMs, String packageName, int virtualUserId,
                          String permission, String action, String outcome, String actor,
                          String reason, long requestId) {
        if (sequence < 1 || timestampMs < 0) throw new IllegalArgumentException("invalid audit identity");
        this.sequence = sequence;
        this.timestampMs = timestampMs;
        this.packageName = required(packageName, "packageName");
        if (virtualUserId < 0 || virtualUserId > 999) throw new IllegalArgumentException("virtualUserId out of range");
        this.virtualUserId = virtualUserId;
        this.permission = policyName(permission, "permission");
        this.action = token(action, "action");
        this.outcome = token(outcome, "outcome");
        this.actor = token(actor, "actor");
        this.reason = bounded(reason, 512);
        if (requestId < 0) throw new IllegalArgumentException("requestId must be non-negative");
        this.requestId = requestId;
    }

    JSONObject toJson() throws Exception {
        JSONObject out = new JSONObject();
        out.put("sequence", sequence); out.put("timestampMs", timestampMs);
        out.put("packageName", packageName); out.put("virtualUserId", virtualUserId);
        out.put("permission", permission); out.put("action", action);
        out.put("outcome", outcome); out.put("actor", actor);
        out.put("reason", reason); out.put("requestId", requestId);
        return out;
    }

    static PermissionAuditRecord fromJson(JSONObject value) throws Exception {
        return new PermissionAuditRecord(value.getLong("sequence"), value.getLong("timestampMs"),
                value.getString("packageName"), value.getInt("virtualUserId"),
                value.getString("permission"), value.getString("action"),
                value.getString("outcome"), value.getString("actor"),
                value.optString("reason", ""), value.optLong("requestId", 0));
    }

    private static String token(String value, String name) {
        String normalized = required(value, name).toUpperCase(java.util.Locale.ROOT);
        if (normalized.length() > 48 || !normalized.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
        return normalized;
    }
    private static String policyName(String value, String name) {
        String normalized = required(value, name);
        if (normalized.length() > 180 || !normalized.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
        return normalized;
    }
    private static String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value;
        if (normalized.length() > maximum) throw new IllegalArgumentException("reason is too long");
        return normalized;
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
