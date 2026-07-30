package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** UsageStats retention, visibility and reporting policy. */
public final class VirtualUsageStatsPolicySnapshot implements Parcelable {
    private final String mode;
    private final boolean enabled;
    private final long retentionMs;
    private final int maximumEvents;
    private final boolean allowReportEvents;
    private final boolean includeOtherPackages;

    public VirtualUsageStatsPolicySnapshot(String mode, boolean enabled, long retentionMs,
            int maximumEvents, boolean allowReportEvents, boolean includeOtherPackages) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.enabled = enabled;
        if (retentionMs < 60_000L || retentionMs > 365L * 24L * 60L * 60L * 1000L) {
            throw new IllegalArgumentException("retentionMs is invalid");
        }
        if (maximumEvents < 0 || maximumEvents > 100_000) {
            throw new IllegalArgumentException("maximumEvents is invalid");
        }
        this.retentionMs = retentionMs;
        this.maximumEvents = maximumEvents;
        this.allowReportEvents = allowReportEvents;
        this.includeOtherPackages = includeOtherPackages;
    }
    private VirtualUsageStatsPolicySnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readLong(), in.readInt(),
                in.readInt() != 0, in.readInt() != 0);
    }
    public String mode() { return mode; }
    public boolean enabled() { return enabled; }
    public long retentionMs() { return retentionMs; }
    public int maximumEvents() { return maximumEvents; }
    public boolean allowReportEvents() { return allowReportEvents; }
    public boolean includeOtherPackages() { return includeOtherPackages; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeInt(enabled ? 1 : 0); out.writeLong(retentionMs);
        out.writeInt(maximumEvents); out.writeInt(allowReportEvents ? 1 : 0);
        out.writeInt(includeOtherPackages ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualUsageStatsPolicySnapshot> CREATOR = new Creator<>() {
        @Override public VirtualUsageStatsPolicySnapshot createFromParcel(Parcel in) { return new VirtualUsageStatsPolicySnapshot(in); }
        @Override public VirtualUsageStatsPolicySnapshot[] newArray(int size) { return new VirtualUsageStatsPolicySnapshot[size]; }
    };
}
