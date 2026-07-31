package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed fail-closed GraphicsStats policy. */
public final class VirtualGraphicsStatsProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean exposeStats;
    private final boolean allowBufferRequests;
    private final int maximumBuffers;
    private final long totalFrames;
    private final long jankyFrames;
    private final long lastResetTimeMs;

    public VirtualGraphicsStatsProfileSnapshot(String mode, boolean exposeStats,
            boolean allowBufferRequests, int maximumBuffers, long totalFrames,
            long jankyFrames, long lastResetTimeMs) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.exposeStats = exposeStats;
        this.allowBufferRequests = allowBufferRequests;
        if (maximumBuffers < 0 || maximumBuffers > 64) {
            throw new IllegalArgumentException("maximumBuffers must be in [0,64]");
        }
        this.maximumBuffers = maximumBuffers;
        this.totalFrames = ContractChecks.nonNegative(totalFrames, "totalFrames");
        this.jankyFrames = ContractChecks.nonNegative(jankyFrames, "jankyFrames");
        if (jankyFrames > totalFrames) throw new IllegalArgumentException("jankyFrames exceeds totalFrames");
        this.lastResetTimeMs = ContractChecks.nonNegative(lastResetTimeMs, "lastResetTimeMs");
    }

    private VirtualGraphicsStatsProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt() != 0, in.readInt(),
                in.readLong(), in.readLong(), in.readLong());
    }

    public String mode() { return mode; }
    public boolean exposeStats() { return exposeStats; }
    public boolean allowBufferRequests() { return allowBufferRequests; }
    public int maximumBuffers() { return maximumBuffers; }
    public long totalFrames() { return totalFrames; }
    public long jankyFrames() { return jankyFrames; }
    public long lastResetTimeMs() { return lastResetTimeMs; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(exposeStats ? 1 : 0);
        out.writeInt(allowBufferRequests ? 1 : 0);
        out.writeInt(maximumBuffers);
        out.writeLong(totalFrames);
        out.writeLong(jankyFrames);
        out.writeLong(lastResetTimeMs);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualGraphicsStatsProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualGraphicsStatsProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualGraphicsStatsProfileSnapshot(in);
        }
        @Override public VirtualGraphicsStatsProfileSnapshot[] newArray(int size) {
            return new VirtualGraphicsStatsProfileSnapshot[size];
        }
    };
}
