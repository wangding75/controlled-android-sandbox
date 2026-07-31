package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed source-side StorageStats policy. */
public final class VirtualStorageStatsProfileSnapshot implements Parcelable {
    private final String mode;
    private final long totalBytes;
    private final long freeBytes;
    private final long cacheQuotaBytes;
    private final long appBytes;
    private final long dataBytes;
    private final long cacheBytes;
    private final long externalCacheBytes;
    private final boolean quotaSupported;
    private final boolean reservedSupported;

    public VirtualStorageStatsProfileSnapshot(String mode, long totalBytes, long freeBytes,
            long cacheQuotaBytes, long appBytes, long dataBytes, long cacheBytes,
            long externalCacheBytes, boolean quotaSupported, boolean reservedSupported) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.totalBytes = ContractChecks.nonNegative(totalBytes, "totalBytes");
        this.freeBytes = ContractChecks.nonNegative(freeBytes, "freeBytes");
        this.cacheQuotaBytes = ContractChecks.nonNegative(cacheQuotaBytes, "cacheQuotaBytes");
        this.appBytes = ContractChecks.nonNegative(appBytes, "appBytes");
        this.dataBytes = ContractChecks.nonNegative(dataBytes, "dataBytes");
        this.cacheBytes = ContractChecks.nonNegative(cacheBytes, "cacheBytes");
        this.externalCacheBytes = ContractChecks.nonNegative(externalCacheBytes, "externalCacheBytes");
        if (freeBytes > totalBytes) throw new IllegalArgumentException("freeBytes exceeds totalBytes");
        this.quotaSupported = quotaSupported;
        this.reservedSupported = reservedSupported;
    }

    private VirtualStorageStatsProfileSnapshot(Parcel in) {
        this(in.readString(), in.readLong(), in.readLong(), in.readLong(), in.readLong(),
                in.readLong(), in.readLong(), in.readLong(), in.readInt() != 0, in.readInt() != 0);
    }

    public String mode() { return mode; }
    public long totalBytes() { return totalBytes; }
    public long freeBytes() { return freeBytes; }
    public long cacheQuotaBytes() { return cacheQuotaBytes; }
    public long appBytes() { return appBytes; }
    public long dataBytes() { return dataBytes; }
    public long cacheBytes() { return cacheBytes; }
    public long externalCacheBytes() { return externalCacheBytes; }
    public boolean quotaSupported() { return quotaSupported; }
    public boolean reservedSupported() { return reservedSupported; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeLong(totalBytes);
        out.writeLong(freeBytes);
        out.writeLong(cacheQuotaBytes);
        out.writeLong(appBytes);
        out.writeLong(dataBytes);
        out.writeLong(cacheBytes);
        out.writeLong(externalCacheBytes);
        out.writeInt(quotaSupported ? 1 : 0);
        out.writeInt(reservedSupported ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualStorageStatsProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualStorageStatsProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualStorageStatsProfileSnapshot(in);
        }
        @Override public VirtualStorageStatsProfileSnapshot[] newArray(int size) {
            return new VirtualStorageStatsProfileSnapshot[size];
        }
    };
}
