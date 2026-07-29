package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Durable notification channel/group metadata in Guest-visible form. */
public final class VirtualNotificationChannelSnapshot implements Parcelable {
    public static final String CHANNEL = "CHANNEL";
    public static final String GROUP = "GROUP";
    private final String kind;
    private final String id;
    private final String groupId;
    private final String packageRevision;
    private final byte[] payload;
    private final long updatedAtMs;

    public VirtualNotificationChannelSnapshot(String kind, String id, String groupId,
                                              byte[] payload, long updatedAtMs) {
        this(kind, id, groupId, "legacy-revision", payload, updatedAtMs);
    }

    public VirtualNotificationChannelSnapshot(String kind, String id, String groupId,
            String packageRevision, byte[] payload, long updatedAtMs) {
        this.kind = requireKind(kind);
        this.id = required(id, "id");
        this.groupId = safe(groupId);
        this.packageRevision = required(packageRevision, "packageRevision");
        this.payload = payload == null ? new byte[0] : payload.clone();
        if (updatedAtMs < 0L) throw new IllegalArgumentException("updatedAtMs must be non-negative");
        this.updatedAtMs = updatedAtMs;
    }
    private VirtualNotificationChannelSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readString(), in.createByteArray(), in.readLong());
    }
    public String kind() { return kind; }
    public String id() { return id; }
    public String groupId() { return groupId; }
    public String packageRevision() { return packageRevision; }
    public byte[] payload() { return payload.clone(); }
    public long updatedAtMs() { return updatedAtMs; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(kind); out.writeString(id); out.writeString(groupId); out.writeString(packageRevision);
        out.writeByteArray(payload); out.writeLong(updatedAtMs);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualNotificationChannelSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualNotificationChannelSnapshot createFromParcel(Parcel in) { return new VirtualNotificationChannelSnapshot(in); }
        @Override public VirtualNotificationChannelSnapshot[] newArray(int size) { return new VirtualNotificationChannelSnapshot[size]; }
    };
    private static String requireKind(String value) {
        String normalized = safe(value);
        if (!CHANNEL.equals(normalized) && !GROUP.equals(normalized)) throw new IllegalArgumentException("invalid notification channel kind");
        return normalized;
    }
    private static String required(String value, String name) {
        String normalized = safe(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
