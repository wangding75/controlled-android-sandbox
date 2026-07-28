package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Durable virtual JobScheduler ownership and marshalled JobInfo state. */
public final class VirtualJobSnapshot implements Parcelable {
    public static final String RESERVED = "RESERVED";
    public static final String SCHEDULED = "SCHEDULED";
    public static final String RUNNING = "RUNNING";
    private final int guestId;
    private final int hostId;
    private final String state;
    private final String ownerProcessName;
    private final long ownerGeneration;
    private final byte[] payload;
    private final long updatedAtMs;

    public VirtualJobSnapshot(int guestId, int hostId, String state, String ownerProcessName,
                              long ownerGeneration, byte[] payload, long updatedAtMs) {
        if (guestId < 0 || hostId < 0 || ownerGeneration < 0L || updatedAtMs < 0L) {
            throw new IllegalArgumentException("job ids/generation/timestamp must be non-negative");
        }
        this.guestId = guestId;
        this.hostId = hostId;
        this.state = requireState(state);
        this.ownerProcessName = required(ownerProcessName, "ownerProcessName");
        this.ownerGeneration = ownerGeneration;
        this.payload = payload == null ? new byte[0] : payload.clone();
        this.updatedAtMs = updatedAtMs;
    }
    private VirtualJobSnapshot(Parcel in) {
        this(in.readInt(), in.readInt(), in.readString(), in.readString(), in.readLong(),
                in.createByteArray(), in.readLong());
    }
    public int guestId() { return guestId; }
    public int hostId() { return hostId; }
    public String state() { return state; }
    public String ownerProcessName() { return ownerProcessName; }
    public long ownerGeneration() { return ownerGeneration; }
    public byte[] payload() { return payload.clone(); }
    public long updatedAtMs() { return updatedAtMs; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(guestId); out.writeInt(hostId); out.writeString(state); out.writeString(ownerProcessName);
        out.writeLong(ownerGeneration); out.writeByteArray(payload); out.writeLong(updatedAtMs);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualJobSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualJobSnapshot createFromParcel(Parcel in) { return new VirtualJobSnapshot(in); }
        @Override public VirtualJobSnapshot[] newArray(int size) { return new VirtualJobSnapshot[size]; }
    };
    private static String requireState(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!RESERVED.equals(normalized) && !SCHEDULED.equals(normalized) && !RUNNING.equals(normalized)) {
            throw new IllegalArgumentException("invalid job state: " + value);
        }
        return normalized;
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
