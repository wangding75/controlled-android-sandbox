package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Durable owned-notification record for one virtual package/user scope. */
public final class VirtualNotificationSnapshot implements Parcelable {
    public static final String RESERVED = "RESERVED";
    public static final String ACTIVE = "ACTIVE";
    private final int guestId;
    private final int hostId;
    private final String guestTag;
    private final String hostTag;
    private final String channelId;
    private final String state;
    private final byte[] payload;
    private final long updatedAtMs;

    public VirtualNotificationSnapshot(int guestId, int hostId, String guestTag, String hostTag,
                                       String channelId, String state, byte[] payload, long updatedAtMs) {
        if (guestId < 0 || hostId < 0 || updatedAtMs < 0L) throw new IllegalArgumentException("notification ids/timestamp must be non-negative");
        this.guestId = guestId;
        this.hostId = hostId;
        this.guestTag = safe(guestTag);
        this.hostTag = safe(hostTag);
        this.channelId = safe(channelId);
        this.state = requireState(state);
        this.payload = payload == null ? new byte[0] : payload.clone();
        this.updatedAtMs = updatedAtMs;
    }
    private VirtualNotificationSnapshot(Parcel in) {
        this(in.readInt(), in.readInt(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.createByteArray(), in.readLong());
    }
    public int guestId() { return guestId; }
    public int hostId() { return hostId; }
    public String guestTag() { return guestTag; }
    public String hostTag() { return hostTag; }
    public String channelId() { return channelId; }
    public String state() { return state; }
    public byte[] payload() { return payload.clone(); }
    public long updatedAtMs() { return updatedAtMs; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(guestId); out.writeInt(hostId); out.writeString(guestTag); out.writeString(hostTag);
        out.writeString(channelId); out.writeString(state); out.writeByteArray(payload); out.writeLong(updatedAtMs);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualNotificationSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualNotificationSnapshot createFromParcel(Parcel in) { return new VirtualNotificationSnapshot(in); }
        @Override public VirtualNotificationSnapshot[] newArray(int size) { return new VirtualNotificationSnapshot[size]; }
    };
    private static String requireState(String value) {
        String normalized = safe(value);
        if (!RESERVED.equals(normalized) && !ACTIVE.equals(normalized)) throw new IllegalArgumentException("invalid notification state: " + value);
        return normalized;
    }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
