package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Durable PendingIntent identity and bounded marshalled Intent payload. */
public final class VirtualPendingIntentSnapshot implements Parcelable {
    public static final String BROADCAST = "BROADCAST";
    public static final String ACTIVITY = "ACTIVITY";
    public static final String ACTIVITY_RESULT = "ACTIVITY_RESULT";
    public static final String SERVICE = "SERVICE";
    public static final String FOREGROUND_SERVICE = "FOREGROUND_SERVICE";

    private final String tokenId;
    private final String kind;
    private final int requestCode;
    private final String action;
    private final String component;
    private final String data;
    private final String filterIdentity;
    private final int flags;
    private final String creatorPackage;
    private final int creatorUid;
    private final String requiredPermission;
    private final String ownerProcessName;
    private final long ownerGeneration;
    private final String packageRevision;
    private final byte[] payload;
    private final int sends;
    private final boolean cancelled;
    private final long updatedAtMs;

    public VirtualPendingIntentSnapshot(String tokenId, String kind, int requestCode,
            String action, String component, String data, String filterIdentity, int flags,
            String creatorPackage, int creatorUid, String requiredPermission,
            String ownerProcessName, long ownerGeneration, String packageRevision,
            byte[] payload, int sends, boolean cancelled, long updatedAtMs) {
        this.tokenId = optional(tokenId);
        this.kind = requireKind(kind);
        if (requestCode < 0 || creatorUid < 0 || ownerGeneration < 0L
                || sends < 0 || updatedAtMs < 0L) {
            throw new IllegalArgumentException("PendingIntent numeric fields must be non-negative");
        }
        this.requestCode = requestCode;
        this.action = optional(action);
        this.component = optional(component);
        this.data = optional(data);
        this.filterIdentity = required(filterIdentity, "filterIdentity");
        this.flags = flags;
        this.creatorPackage = required(creatorPackage, "creatorPackage");
        this.creatorUid = creatorUid;
        this.requiredPermission = optional(requiredPermission);
        this.ownerProcessName = required(ownerProcessName, "ownerProcessName");
        this.ownerGeneration = ownerGeneration;
        this.packageRevision = required(packageRevision, "packageRevision");
        this.payload = payload == null ? new byte[0] : payload.clone();
        this.sends = sends;
        this.cancelled = cancelled;
        this.updatedAtMs = updatedAtMs;
    }

    private VirtualPendingIntentSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readInt(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.readInt(), in.readString(), in.readInt(), in.readString(),
                in.readString(), in.readLong(), in.readString(), in.createByteArray(),
                in.readInt(), in.readInt() != 0, in.readLong());
    }

    public String tokenId() { return tokenId; }
    public String kind() { return kind; }
    public int requestCode() { return requestCode; }
    public String action() { return action; }
    public String component() { return component; }
    public String data() { return data; }
    public String filterIdentity() { return filterIdentity; }
    public int flags() { return flags; }
    public String creatorPackage() { return creatorPackage; }
    public int creatorUid() { return creatorUid; }
    public String requiredPermission() { return requiredPermission; }
    public String ownerProcessName() { return ownerProcessName; }
    public long ownerGeneration() { return ownerGeneration; }
    public String packageRevision() { return packageRevision; }
    public byte[] payload() { return payload.clone(); }
    public int sends() { return sends; }
    public boolean cancelled() { return cancelled; }
    public long updatedAtMs() { return updatedAtMs; }

    @Override public void writeToParcel(Parcel out, int parcelFlags) {
        out.writeString(tokenId); out.writeString(kind); out.writeInt(requestCode);
        out.writeString(action); out.writeString(component); out.writeString(data); out.writeString(filterIdentity); out.writeInt(flags);
        out.writeString(creatorPackage); out.writeInt(creatorUid); out.writeString(requiredPermission);
        out.writeString(ownerProcessName); out.writeLong(ownerGeneration); out.writeString(packageRevision);
        out.writeByteArray(payload); out.writeInt(sends); out.writeInt(cancelled ? 1 : 0); out.writeLong(updatedAtMs);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualPendingIntentSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualPendingIntentSnapshot createFromParcel(Parcel in) {
            return new VirtualPendingIntentSnapshot(in);
        }
        @Override public VirtualPendingIntentSnapshot[] newArray(int size) {
            return new VirtualPendingIntentSnapshot[size];
        }
    };

    private static String requireKind(String value) {
        String normalized = required(value, "kind");
        return switch (normalized) {
            case BROADCAST, ACTIVITY, ACTIVITY_RESULT, SERVICE, FOREGROUND_SERVICE -> normalized;
            default -> throw new IllegalArgumentException("Unsupported PendingIntent kind: " + normalized);
        };
    }
    private static String required(String value, String name) {
        String normalized = optional(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
    private static String optional(String value) { return value == null ? "" : value.trim(); }
}
