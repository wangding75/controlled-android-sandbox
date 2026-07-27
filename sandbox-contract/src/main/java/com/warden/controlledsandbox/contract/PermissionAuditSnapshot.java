package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed bounded audit event for permission workflow changes. */
public final class PermissionAuditSnapshot implements Parcelable {
    private final long sequence;
    private final long timestampMs;
    private final long requestId;
    private final String packageName;
    private final String permission;
    private final String action;
    private final String outcome;
    private final String actor;
    private final String reason;
    private final int virtualUserId;

    public PermissionAuditSnapshot(long sequence, long timestampMs, String packageName,
                                   int virtualUserId, String permission, String action,
                                   String outcome, String actor, String reason, long requestId) {
        if (sequence < 1 || timestampMs < 0 || requestId < 0) {
            throw new IllegalArgumentException("invalid audit identity");
        }
        this.sequence = sequence;
        this.timestampMs = timestampMs;
        this.packageName = required(packageName, "packageName");
        if (virtualUserId < 0 || virtualUserId > 999) {
            throw new IllegalArgumentException("virtualUserId out of range");
        }
        this.virtualUserId = virtualUserId;
        this.permission = policyName(permission, "permission");
        this.action = token(action, "action");
        this.outcome = token(outcome, "outcome");
        this.actor = token(actor, "actor");
        this.reason = bounded(reason, 512);
        this.requestId = requestId;
    }

    private PermissionAuditSnapshot(Parcel in) {
        this(in.readLong(), in.readLong(), in.readString(), in.readInt(), in.readString(),
                in.readString(), in.readString(), in.readString(), in.readString(), in.readLong());
    }

    public long sequence() { return sequence; }
    public long timestampMs() { return timestampMs; }
    public String packageName() { return packageName; }
    public int virtualUserId() { return virtualUserId; }
    public String permission() { return permission; }
    public String action() { return action; }
    public String outcome() { return outcome; }
    public String actor() { return actor; }
    public String reason() { return reason; }
    public long requestId() { return requestId; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(sequence);
        out.writeLong(timestampMs);
        out.writeString(packageName);
        out.writeInt(virtualUserId);
        out.writeString(permission);
        out.writeString(action);
        out.writeString(outcome);
        out.writeString(actor);
        out.writeString(reason);
        out.writeLong(requestId);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<PermissionAuditSnapshot> CREATOR = new Creator<>() {
        @Override public PermissionAuditSnapshot createFromParcel(Parcel in) {
            return new PermissionAuditSnapshot(in);
        }
        @Override public PermissionAuditSnapshot[] newArray(int size) {
            return new PermissionAuditSnapshot[size];
        }
    };

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
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
