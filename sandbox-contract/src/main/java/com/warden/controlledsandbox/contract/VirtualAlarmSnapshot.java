package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Persisted virtual alarm metadata and marshalled delivery token. */
public final class VirtualAlarmSnapshot implements Parcelable {
    private final String alarmId;
    private final long triggerAtMs;
    private final long intervalMs;
    private final byte[] tokenPayload;

    public VirtualAlarmSnapshot(String alarmId, long triggerAtMs, long intervalMs, byte[] tokenPayload) {
        if (alarmId == null || alarmId.trim().isEmpty()) throw new IllegalArgumentException("alarmId is required");
        if (triggerAtMs < 0L || intervalMs < 0L) throw new IllegalArgumentException("alarm times must be non-negative");
        this.alarmId = alarmId.trim();
        this.triggerAtMs = triggerAtMs;
        this.intervalMs = intervalMs;
        this.tokenPayload = tokenPayload == null ? new byte[0] : tokenPayload.clone();
    }
    private VirtualAlarmSnapshot(Parcel in) {
        this(in.readString(), in.readLong(), in.readLong(), in.createByteArray());
    }
    public String alarmId() { return alarmId; }
    public long triggerAtMs() { return triggerAtMs; }
    public long intervalMs() { return intervalMs; }
    public byte[] tokenPayload() { return tokenPayload.clone(); }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(alarmId); out.writeLong(triggerAtMs); out.writeLong(intervalMs); out.writeByteArray(tokenPayload);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualAlarmSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualAlarmSnapshot createFromParcel(Parcel in) { return new VirtualAlarmSnapshot(in); }
        @Override public VirtualAlarmSnapshot[] newArray(int size) { return new VirtualAlarmSnapshot[size]; }
    };
}
