package com.warden.controlledsandbox;

import org.json.JSONException;
import org.json.JSONObject;

final class SandboxInstance {
    final String packageName;
    final int virtualUserId;
    final String displayName;
    final long createdAt;
    String lastRuntimeStatus;
    long lastRuntimeAt;

    SandboxInstance(String packageName, int virtualUserId, String displayName, long createdAt,
                    String lastRuntimeStatus, long lastRuntimeAt) {
        if (packageName == null || packageName.trim().isEmpty()) throw new IllegalArgumentException("packageName is required");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        this.packageName = packageName;
        this.virtualUserId = virtualUserId;
        this.displayName = displayName == null || displayName.trim().isEmpty() ? "Instance " + virtualUserId : displayName;
        this.createdAt = createdAt;
        this.lastRuntimeStatus = lastRuntimeStatus == null ? "NOT_TESTED" : lastRuntimeStatus;
        this.lastRuntimeAt = lastRuntimeAt;
    }

    SandboxInstance withStatus(String status, long atMs) {
        return new SandboxInstance(packageName, virtualUserId, displayName, createdAt,
                status, atMs);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("packageName", packageName).put("virtualUserId", virtualUserId)
                .put("displayName", displayName).put("createdAt", createdAt)
                .put("lastRuntimeStatus", lastRuntimeStatus).put("lastRuntimeAt", lastRuntimeAt);
    }

    static SandboxInstance fromJson(JSONObject value) throws JSONException {
        return new SandboxInstance(value.getString("packageName"), value.optInt("virtualUserId", 0),
                value.optString("displayName"), value.optLong("createdAt"),
                value.optString("lastRuntimeStatus", "NOT_TESTED"), value.optLong("lastRuntimeAt"));
    }
}
