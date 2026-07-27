package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Set;

/** Typed runtime-permission request state returned by Package Service. */
public final class RuntimePermissionRequestSnapshot implements Parcelable {
    private static final Set<String> STATES = Set.of("PENDING", "GRANTED", "DENIED", "CANCELLED");

    private final long requestId;
    private final String packageName;
    private final int virtualUserId;
    private final String permission;
    private final String appOpName;
    private final String state;
    private final boolean hostGranted;
    private final int requestCode;
    private final String sessionId;
    private final long generation;
    private final long createdAtMs;
    private final long resolvedAtMs;
    private final String reason;

    public RuntimePermissionRequestSnapshot(long requestId, String packageName, int virtualUserId,
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
        this.state = requestState(state);
        this.hostGranted = hostGranted;
        if (requestCode < -1 || requestCode > 65535) {
            throw new IllegalArgumentException("requestCode out of range");
        }
        this.requestCode = requestCode;
        this.sessionId = value(sessionId).trim();
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        this.generation = generation;
        if (createdAtMs < 0 || resolvedAtMs < 0
                || (resolvedAtMs != 0 && resolvedAtMs < createdAtMs)) {
            throw new IllegalArgumentException("invalid permission request timestamps");
        }
        if ("PENDING".equals(this.state) && resolvedAtMs != 0) {
            throw new IllegalArgumentException("pending request cannot have resolvedAtMs");
        }
        if (!"PENDING".equals(this.state) && resolvedAtMs == 0) {
            throw new IllegalArgumentException("resolved request requires resolvedAtMs");
        }
        if ("GRANTED".equals(this.state) && !hostGranted) {
            throw new IllegalArgumentException("granted request requires host capability");
        }
        this.createdAtMs = createdAtMs;
        this.resolvedAtMs = resolvedAtMs;
        this.reason = bounded(reason, 512);
    }

    private RuntimePermissionRequestSnapshot(Parcel in) {
        this(in.readLong(), in.readString(), in.readInt(), in.readString(), in.readString(),
                in.readString(), in.readInt() != 0, in.readInt(), in.readString(), in.readLong(),
                in.readLong(), in.readLong(), in.readString());
    }

    public long requestId() { return requestId; }
    public String packageName() { return packageName; }
    public int virtualUserId() { return virtualUserId; }
    public String permission() { return permission; }
    public String appOpName() { return appOpName; }
    public String state() { return state; }
    public boolean hostGranted() { return hostGranted; }
    public int requestCode() { return requestCode; }
    public String sessionId() { return sessionId; }
    public long generation() { return generation; }
    public long createdAtMs() { return createdAtMs; }
    public long resolvedAtMs() { return resolvedAtMs; }
    public String reason() { return reason; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(requestId);
        out.writeString(packageName);
        out.writeInt(virtualUserId);
        out.writeString(permission);
        out.writeString(appOpName);
        out.writeString(state);
        out.writeInt(hostGranted ? 1 : 0);
        out.writeInt(requestCode);
        out.writeString(sessionId);
        out.writeLong(generation);
        out.writeLong(createdAtMs);
        out.writeLong(resolvedAtMs);
        out.writeString(reason);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<RuntimePermissionRequestSnapshot> CREATOR = new Creator<>() {
        @Override public RuntimePermissionRequestSnapshot createFromParcel(Parcel in) {
            return new RuntimePermissionRequestSnapshot(in);
        }
        @Override public RuntimePermissionRequestSnapshot[] newArray(int size) {
            return new RuntimePermissionRequestSnapshot[size];
        }
    };

    private static String requestState(String value) {
        String normalized = required(value, "state").toUpperCase(java.util.Locale.ROOT);
        if (!STATES.contains(normalized)) {
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
        if (normalized.length() > maximum) throw new IllegalArgumentException("reason is too long");
        return normalized;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String value(String value) { return value == null ? "" : value; }
}
