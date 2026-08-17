package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Durable typed alarm descriptor for one virtual package/user/revision scope. */
public final class VirtualAlarmSnapshot implements Parcelable {
    public static final String LISTENER = "LISTENER";
    public static final String PENDING_INTENT = "PENDING_INTENT";

    private final String alarmId;
    private final long triggerAtMs;
    private final long intervalMs;
    private final boolean exact;
    private final boolean allowWhileIdle;
    private final String deliveryPath;
    private final String pendingIntentTokenId;
    private final String ownerProcessName;
    private final long ownerGeneration;
    private final String packageRevision;
    private final byte[] tokenPayload;
    private final int deliveryCount;
    private final long updatedAtMs;
    private final boolean alarmClock;
    private final byte[] alarmClockPayload;

    /** Legacy constructor retained for schema-1/2/3 tests and local authorities. */
    public VirtualAlarmSnapshot(String alarmId, long triggerAtMs, long intervalMs, byte[] tokenPayload) {
        this(alarmId, triggerAtMs, intervalMs, false, false, LISTENER, "", "legacy", 0L,
                "legacy-revision", tokenPayload, 0, 0L);
    }

    public VirtualAlarmSnapshot(String alarmId, long triggerAtMs, long intervalMs,
            boolean exact, boolean allowWhileIdle, String deliveryPath,
            String pendingIntentTokenId, String ownerProcessName, long ownerGeneration,
            String packageRevision, byte[] tokenPayload, int deliveryCount, long updatedAtMs) {
        this(alarmId, triggerAtMs, intervalMs, exact, allowWhileIdle, deliveryPath,
                pendingIntentTokenId, ownerProcessName, ownerGeneration, packageRevision,
                tokenPayload, deliveryCount, updatedAtMs, false, new byte[0]);
    }

    /** Full schema constructor including AlarmClockInfo's user-visible show PendingIntent. */
    public VirtualAlarmSnapshot(String alarmId, long triggerAtMs, long intervalMs,
            boolean exact, boolean allowWhileIdle, String deliveryPath,
            String pendingIntentTokenId, String ownerProcessName, long ownerGeneration,
            String packageRevision, byte[] tokenPayload, int deliveryCount, long updatedAtMs,
            boolean alarmClock, byte[] alarmClockPayload) {
        if (alarmId == null || alarmId.trim().isEmpty()) throw new IllegalArgumentException("alarmId is required");
        if (triggerAtMs < 0L || intervalMs < 0L || ownerGeneration < 0L
                || deliveryCount < 0 || updatedAtMs < 0L) {
            throw new IllegalArgumentException("alarm times/identity/count must be non-negative");
        }
        this.alarmId = alarmId.trim();
        this.triggerAtMs = triggerAtMs;
        this.intervalMs = intervalMs;
        this.exact = exact;
        this.allowWhileIdle = allowWhileIdle;
        this.deliveryPath = requirePath(deliveryPath);
        this.pendingIntentTokenId = safe(pendingIntentTokenId);
        if (PENDING_INTENT.equals(this.deliveryPath) && this.pendingIntentTokenId.isEmpty()) {
            throw new IllegalArgumentException("pendingIntentTokenId is required for PendingIntent alarms");
        }
        this.ownerProcessName = required(ownerProcessName, "ownerProcessName");
        this.ownerGeneration = ownerGeneration;
        this.packageRevision = required(packageRevision, "packageRevision");
        this.tokenPayload = tokenPayload == null ? new byte[0] : tokenPayload.clone();
        this.deliveryCount = deliveryCount;
        this.updatedAtMs = updatedAtMs;
        this.alarmClock = alarmClock;
        this.alarmClockPayload = alarmClockPayload == null ? new byte[0] : alarmClockPayload.clone();
    }

    private VirtualAlarmSnapshot(Parcel in) {
        this(in.readString(), in.readLong(), in.readLong(), in.readInt() != 0, in.readInt() != 0,
                in.readString(), in.readString(), in.readString(), in.readLong(), in.readString(),
                in.createByteArray(), in.readInt(), in.readLong(), in.readInt() != 0,
                in.createByteArray());
    }

    public String alarmId() { return alarmId; }
    public long triggerAtMs() { return triggerAtMs; }
    public long intervalMs() { return intervalMs; }
    public boolean exact() { return exact; }
    public boolean allowWhileIdle() { return allowWhileIdle; }
    public String deliveryPath() { return deliveryPath; }
    public String pendingIntentTokenId() { return pendingIntentTokenId; }
    public String ownerProcessName() { return ownerProcessName; }
    public long ownerGeneration() { return ownerGeneration; }
    public String packageRevision() { return packageRevision; }
    public byte[] tokenPayload() { return tokenPayload.clone(); }
    public int deliveryCount() { return deliveryCount; }
    public long updatedAtMs() { return updatedAtMs; }
    public boolean alarmClock() { return alarmClock; }
    public byte[] alarmClockPayload() { return alarmClockPayload.clone(); }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(alarmId); out.writeLong(triggerAtMs); out.writeLong(intervalMs);
        out.writeInt(exact ? 1 : 0); out.writeInt(allowWhileIdle ? 1 : 0);
        out.writeString(deliveryPath); out.writeString(pendingIntentTokenId);
        out.writeString(ownerProcessName); out.writeLong(ownerGeneration); out.writeString(packageRevision);
        out.writeByteArray(tokenPayload); out.writeInt(deliveryCount); out.writeLong(updatedAtMs);
        out.writeInt(alarmClock ? 1 : 0); out.writeByteArray(alarmClockPayload);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualAlarmSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualAlarmSnapshot createFromParcel(Parcel in) { return new VirtualAlarmSnapshot(in); }
        @Override public VirtualAlarmSnapshot[] newArray(int size) { return new VirtualAlarmSnapshot[size]; }
    };

    private static String requirePath(String value) {
        String normalized = safe(value).toUpperCase(java.util.Locale.ROOT);
        if (!LISTENER.equals(normalized) && !PENDING_INTENT.equals(normalized)) {
            throw new IllegalArgumentException("invalid alarm delivery path: " + value);
        }
        return normalized;
    }
    private static String required(String value, String name) {
        String normalized = safe(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
