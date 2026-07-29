package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Durable virtual JobScheduler ownership, constraints and retry policy. */
public final class VirtualJobSnapshot implements Parcelable {
    public static final String RESERVED = "RESERVED";
    public static final String SCHEDULED = "SCHEDULED";
    public static final String DISPATCHING = "DISPATCHING";
    public static final String RUNNING = "RUNNING";

    public static final int NETWORK_NONE = 0;
    public static final int NETWORK_ANY = 1;
    public static final int NETWORK_UNMETERED = 2;
    public static final int NETWORK_NOT_ROAMING = 3;
    public static final int NETWORK_CELLULAR = 4;
    public static final int NETWORK_METERED = 5;
    public static final int BACKOFF_LINEAR = 0;
    public static final int BACKOFF_EXPONENTIAL = 1;

    private final int guestId;
    private final int hostId;
    private final String state;
    private final String ownerProcessName;
    private final long ownerGeneration;
    private final String packageRevision;
    private final int requiredNetworkType;
    private final boolean requiresCharging;
    private final boolean requiresBatteryNotLow;
    private final boolean requiresStorageNotLow;
    private final boolean requiresDeviceIdle;
    private final boolean periodic;
    private final long intervalMs;
    private final long flexMs;
    private final long minimumLatencyMs;
    private final long overrideDeadlineMs;
    private final boolean expedited;
    private final boolean persisted;
    private final int backoffPolicy;
    private final long initialBackoffMs;
    private final int failureCount;
    private final long nextRunAtMs;
    private final long lastFailureAtMs;
    private final byte[] payload;
    private final long updatedAtMs;

    /** Compatibility constructor for schema 1-4 callers. */
    public VirtualJobSnapshot(int guestId, int hostId, String state, String ownerProcessName,
                              long ownerGeneration, byte[] payload, long updatedAtMs) {
        this(guestId, hostId, state, ownerProcessName, ownerGeneration, "legacy-revision",
                NETWORK_NONE, false, false, false, false, false, 0L, 0L, 0L, 0L,
                false, false, BACKOFF_EXPONENTIAL, 30_000L, 0, 0L, 0L, payload, updatedAtMs);
    }

    public VirtualJobSnapshot(int guestId, int hostId, String state, String ownerProcessName,
                              long ownerGeneration, String packageRevision, int requiredNetworkType,
                              boolean requiresCharging, boolean requiresBatteryNotLow,
                              boolean requiresStorageNotLow, boolean requiresDeviceIdle,
                              boolean periodic, long intervalMs, long flexMs,
                              long minimumLatencyMs, long overrideDeadlineMs,
                              boolean expedited, boolean persisted, int backoffPolicy,
                              long initialBackoffMs, int failureCount, long nextRunAtMs,
                              long lastFailureAtMs, byte[] payload, long updatedAtMs) {
        if (guestId < 0 || hostId < 0 || ownerGeneration < 0L || updatedAtMs < 0L
                || failureCount < 0 || nextRunAtMs < 0L || lastFailureAtMs < 0L) {
            throw new IllegalArgumentException("job identity/counters/timestamps must be non-negative");
        }
        if (requiredNetworkType < NETWORK_NONE || requiredNetworkType > NETWORK_METERED) {
            throw new IllegalArgumentException("invalid requiredNetworkType");
        }
        if (backoffPolicy != BACKOFF_LINEAR && backoffPolicy != BACKOFF_EXPONENTIAL) {
            throw new IllegalArgumentException("invalid backoffPolicy");
        }
        if (intervalMs < 0L || flexMs < 0L || minimumLatencyMs < 0L
                || overrideDeadlineMs < 0L || initialBackoffMs < 0L) {
            throw new IllegalArgumentException("job timing must be non-negative");
        }
        if (!periodic && (intervalMs != 0L || flexMs != 0L)) {
            throw new IllegalArgumentException("non-periodic job cannot have interval/flex");
        }
        if (periodic && intervalMs == 0L) throw new IllegalArgumentException("periodic interval is required");
        if (flexMs > intervalMs) throw new IllegalArgumentException("periodic flex exceeds interval");
        this.guestId = guestId;
        this.hostId = hostId;
        this.state = requireState(state);
        this.ownerProcessName = required(ownerProcessName, "ownerProcessName");
        this.ownerGeneration = ownerGeneration;
        this.packageRevision = required(packageRevision, "packageRevision");
        this.requiredNetworkType = requiredNetworkType;
        this.requiresCharging = requiresCharging;
        this.requiresBatteryNotLow = requiresBatteryNotLow;
        this.requiresStorageNotLow = requiresStorageNotLow;
        this.requiresDeviceIdle = requiresDeviceIdle;
        this.periodic = periodic;
        this.intervalMs = intervalMs;
        this.flexMs = flexMs;
        this.minimumLatencyMs = minimumLatencyMs;
        this.overrideDeadlineMs = overrideDeadlineMs;
        this.expedited = expedited;
        this.persisted = persisted;
        this.backoffPolicy = backoffPolicy;
        this.initialBackoffMs = initialBackoffMs;
        this.failureCount = failureCount;
        this.nextRunAtMs = nextRunAtMs;
        this.lastFailureAtMs = lastFailureAtMs;
        this.payload = payload == null ? new byte[0] : payload.clone();
        this.updatedAtMs = updatedAtMs;
    }

    private VirtualJobSnapshot(Parcel in) {
        this(in.readInt(), in.readInt(), in.readString(), in.readString(), in.readLong(),
                in.readString(), in.readInt(), in.readInt() != 0, in.readInt() != 0,
                in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.readLong(), in.readLong(), in.readLong(), in.readLong(),
                in.readInt() != 0, in.readInt() != 0, in.readInt(), in.readLong(),
                in.readInt(), in.readLong(), in.readLong(), in.createByteArray(), in.readLong());
    }

    public int guestId() { return guestId; }
    public int hostId() { return hostId; }
    public String state() { return state; }
    public String ownerProcessName() { return ownerProcessName; }
    public long ownerGeneration() { return ownerGeneration; }
    public String packageRevision() { return packageRevision; }
    public int requiredNetworkType() { return requiredNetworkType; }
    public boolean requiresCharging() { return requiresCharging; }
    public boolean requiresBatteryNotLow() { return requiresBatteryNotLow; }
    public boolean requiresStorageNotLow() { return requiresStorageNotLow; }
    public boolean requiresDeviceIdle() { return requiresDeviceIdle; }
    public boolean periodic() { return periodic; }
    public long intervalMs() { return intervalMs; }
    public long flexMs() { return flexMs; }
    public long minimumLatencyMs() { return minimumLatencyMs; }
    public long overrideDeadlineMs() { return overrideDeadlineMs; }
    public boolean expedited() { return expedited; }
    public boolean persisted() { return persisted; }
    public int backoffPolicy() { return backoffPolicy; }
    public long initialBackoffMs() { return initialBackoffMs; }
    public int failureCount() { return failureCount; }
    public long nextRunAtMs() { return nextRunAtMs; }
    public long lastFailureAtMs() { return lastFailureAtMs; }
    public byte[] payload() { return payload.clone(); }
    public long updatedAtMs() { return updatedAtMs; }

    public VirtualJobSnapshot withHostState(int newHostId, String newState, String processName,
                                            long generation, String revision, int failures,
                                            long nextRun, long lastFailure, long updated) {
        return new VirtualJobSnapshot(guestId, newHostId, newState, processName, generation, revision,
                requiredNetworkType, requiresCharging, requiresBatteryNotLow, requiresStorageNotLow,
                requiresDeviceIdle, periodic, intervalMs, flexMs, minimumLatencyMs,
                overrideDeadlineMs, expedited, persisted, backoffPolicy, initialBackoffMs,
                failures, nextRun, lastFailure, payload, updated);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(guestId); out.writeInt(hostId); out.writeString(state); out.writeString(ownerProcessName);
        out.writeLong(ownerGeneration); out.writeString(packageRevision); out.writeInt(requiredNetworkType);
        out.writeInt(requiresCharging ? 1 : 0); out.writeInt(requiresBatteryNotLow ? 1 : 0);
        out.writeInt(requiresStorageNotLow ? 1 : 0); out.writeInt(requiresDeviceIdle ? 1 : 0);
        out.writeInt(periodic ? 1 : 0); out.writeLong(intervalMs); out.writeLong(flexMs);
        out.writeLong(minimumLatencyMs); out.writeLong(overrideDeadlineMs);
        out.writeInt(expedited ? 1 : 0); out.writeInt(persisted ? 1 : 0);
        out.writeInt(backoffPolicy); out.writeLong(initialBackoffMs); out.writeInt(failureCount);
        out.writeLong(nextRunAtMs); out.writeLong(lastFailureAtMs); out.writeByteArray(payload);
        out.writeLong(updatedAtMs);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualJobSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualJobSnapshot createFromParcel(Parcel in) { return new VirtualJobSnapshot(in); }
        @Override public VirtualJobSnapshot[] newArray(int size) { return new VirtualJobSnapshot[size]; }
    };

    private static String requireState(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!RESERVED.equals(normalized) && !SCHEDULED.equals(normalized)
                && !DISPATCHING.equals(normalized) && !RUNNING.equals(normalized)) {
            throw new IllegalArgumentException("invalid job state: " + value);
        }
        return normalized;
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
