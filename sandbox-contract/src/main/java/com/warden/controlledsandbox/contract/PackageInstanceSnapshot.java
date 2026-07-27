package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Immutable Binder representation of one virtual package instance. */
public final class PackageInstanceSnapshot implements Parcelable {
    private final String packageName;
    private final int virtualUserId;
    private final String displayName;
    private final long createdAt;
    private final String lastRuntimeStatus;
    private final long lastRuntimeAt;

    public PackageInstanceSnapshot(String packageName, int virtualUserId, String displayName,
                                   long createdAt, String lastRuntimeStatus, long lastRuntimeAt) {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("packageName is required");
        }
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        this.packageName = packageName;
        this.virtualUserId = virtualUserId;
        this.displayName = displayName == null ? "" : displayName;
        this.createdAt = createdAt;
        this.lastRuntimeStatus = lastRuntimeStatus == null ? "" : lastRuntimeStatus;
        this.lastRuntimeAt = lastRuntimeAt;
    }

    private PackageInstanceSnapshot(Parcel in) {
        this(in.readString(), in.readInt(), in.readString(), in.readLong(),
                in.readString(), in.readLong());
    }

    public String packageName() { return packageName; }
    public int virtualUserId() { return virtualUserId; }
    public String displayName() { return displayName; }
    public long createdAt() { return createdAt; }
    public String lastRuntimeStatus() { return lastRuntimeStatus; }
    public long lastRuntimeAt() { return lastRuntimeAt; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(packageName); out.writeInt(virtualUserId); out.writeString(displayName);
        out.writeLong(createdAt); out.writeString(lastRuntimeStatus); out.writeLong(lastRuntimeAt);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<PackageInstanceSnapshot> CREATOR = new Creator<>() {
        @Override public PackageInstanceSnapshot createFromParcel(Parcel in) {
            return new PackageInstanceSnapshot(in);
        }
        @Override public PackageInstanceSnapshot[] newArray(int size) {
            return new PackageInstanceSnapshot[size];
        }
    };
}
