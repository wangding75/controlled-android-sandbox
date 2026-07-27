package com.warden.controlledsandbox;

import java.util.List;
import org.json.JSONObject;

/** Immutable latest runtime-permission request owned by the atomic package catalog. */
final class RuntimePermissionRequestRecord {
    static final String PENDING = "PENDING";
    static final String GRANTED = "GRANTED";
    static final String DENIED = "DENIED";
    static final String CANCELLED = "CANCELLED";

    final long requestId;
    final String packageName;
    final int virtualUserId;
    final String permission;
    final String appOpName;
    final String state;
    final boolean hostGranted;
    final int requestCode;
    final String sessionId;
    final long generation;
    final long createdAtMs;
    final long resolvedAtMs;
    final String reason;

    RuntimePermissionRequestRecord(long requestId, String packageName, int virtualUserId,
                                   String permission, String appOpName, String state,
                                   boolean hostGranted, int requestCode, String sessionId,
                                   long generation, long createdAtMs, long resolvedAtMs,
                                   String reason) {
        if (requestId < 1) throw new IllegalArgumentException("requestId must be positive");
        this.requestId = requestId;
        this.packageName = required(packageName, "packageName");
        if (virtualUserId < 0 || virtualUserId > 999) {
            throw new IllegalArgumentException("virtualUserId out of range");
        }
        this.virtualUserId = virtualUserId;
        this.permission = policyName(permission, "permission");
        this.appOpName = optionalPolicyName(appOpName);
        this.state = state(state);
        this.hostGranted = hostGranted;
        if (requestCode < -1 || requestCode > 65535) {
            throw new IllegalArgumentException("requestCode out of range");
        }
        this.requestCode = requestCode;
        this.sessionId = value(sessionId);
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        this.generation = generation;
        if (createdAtMs < 0 || resolvedAtMs < 0 || resolvedAtMs < createdAtMs && resolvedAtMs != 0) {
            throw new IllegalArgumentException("invalid permission request timestamps");
        }
        if (PENDING.equals(this.state) && resolvedAtMs != 0) {
            throw new IllegalArgumentException("pending request cannot have resolvedAtMs");
        }
        if (!PENDING.equals(this.state) && resolvedAtMs == 0) {
            throw new IllegalArgumentException("resolved request requires resolvedAtMs");
        }
        if (GRANTED.equals(this.state) && !hostGranted) {
            throw new IllegalArgumentException("granted request requires host capability");
        }
        this.createdAtMs = createdAtMs;
        this.resolvedAtMs = resolvedAtMs;
        this.reason = bounded(reason, 512);
    }

    RuntimePermissionRequestRecord resolve(String outcome, boolean currentHostGranted,
                                           long nowMs, String resolutionReason) {
        if (!PENDING.equals(state)) throw new IllegalStateException("permission request is already resolved");
        String normalized = state(outcome);
        if (PENDING.equals(normalized)) throw new IllegalArgumentException("resolution cannot remain pending");
        if (GRANTED.equals(normalized) && !currentHostGranted) {
            throw new SecurityException("HOST_PERMISSION_CAPABILITY_NOT_GRANTED");
        }
        return new RuntimePermissionRequestRecord(requestId, packageName, virtualUserId,
                permission, appOpName, normalized, currentHostGranted, requestCode,
                sessionId, generation, createdAtMs, nowMs, resolutionReason);
    }

    JSONObject toJson() throws Exception {
        JSONObject out = new JSONObject();
        out.put("requestId", requestId);
        out.put("packageName", packageName);
        out.put("virtualUserId", virtualUserId);
        out.put("permission", permission);
        out.put("appOpName", appOpName);
        out.put("state", state);
        out.put("hostGranted", hostGranted);
        out.put("requestCode", requestCode);
        out.put("sessionId", sessionId);
        out.put("generation", generation);
        out.put("createdAtMs", createdAtMs);
        out.put("resolvedAtMs", resolvedAtMs);
        out.put("reason", reason);
        return out;
    }

    static RuntimePermissionRequestRecord fromJson(JSONObject value) throws Exception {
        return new RuntimePermissionRequestRecord(
                value.getLong("requestId"), value.getString("packageName"),
                value.getInt("virtualUserId"), value.getString("permission"),
                value.optString("appOpName", ""), value.getString("state"),
                value.optBoolean("hostGranted", false), value.optInt("requestCode", -1),
                value.optString("sessionId", ""), value.optLong("generation", 0),
                value.getLong("createdAtMs"), value.optLong("resolvedAtMs", 0),
                value.optString("reason", ""));
    }

    static String state(String value) {
        String normalized = required(value, "state").toUpperCase(java.util.Locale.ROOT);
        if (!List.of(PENDING, GRANTED, DENIED, CANCELLED).contains(normalized)) {
            throw new IllegalArgumentException("Unsupported permission request state: " + value);
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
    private static String optionalPolicyName(String value) {
        String normalized = value(value).trim();
        return normalized.isEmpty() ? "" : policyName(normalized, "appOpName");
    }
    private static String bounded(String value, int maximum) {
        String normalized = value(value);
        if (normalized.length() > maximum) throw new IllegalArgumentException("text is too long");
        return normalized;
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
    private static String value(String value) { return value == null ? "" : value; }
}
