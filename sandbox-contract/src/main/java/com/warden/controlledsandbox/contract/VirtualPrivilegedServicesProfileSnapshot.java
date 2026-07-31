package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Aggregate Search/Storage/Graphics/ContextHub/PDB/SystemUpdate profile. */
public final class VirtualPrivilegedServicesProfileSnapshot implements Parcelable {
    private final long policyVersion;
    private final long updatedAtMs;
    private final VirtualSearchProfileSnapshot search;
    private final VirtualStorageStatsProfileSnapshot storageStats;
    private final VirtualGraphicsStatsProfileSnapshot graphicsStats;
    private final VirtualContextHubProfileSnapshot contextHub;
    private final VirtualPersistentDataBlockProfileSnapshot persistentDataBlock;
    private final VirtualSystemUpdateProfileSnapshot systemUpdate;

    public VirtualPrivilegedServicesProfileSnapshot(long policyVersion, long updatedAtMs,
            VirtualSearchProfileSnapshot search,
            VirtualStorageStatsProfileSnapshot storageStats,
            VirtualGraphicsStatsProfileSnapshot graphicsStats,
            VirtualContextHubProfileSnapshot contextHub,
            VirtualPersistentDataBlockProfileSnapshot persistentDataBlock,
            VirtualSystemUpdateProfileSnapshot systemUpdate) {
        if (policyVersion < 1L || updatedAtMs < 0L) {
            throw new IllegalArgumentException("privileged profile version/time is invalid");
        }
        this.policyVersion = policyVersion;
        this.updatedAtMs = updatedAtMs;
        this.search = java.util.Objects.requireNonNull(search, "search");
        this.storageStats = java.util.Objects.requireNonNull(storageStats, "storageStats");
        this.graphicsStats = java.util.Objects.requireNonNull(graphicsStats, "graphicsStats");
        this.contextHub = java.util.Objects.requireNonNull(contextHub, "contextHub");
        this.persistentDataBlock = java.util.Objects.requireNonNull(
                persistentDataBlock, "persistentDataBlock");
        this.systemUpdate = java.util.Objects.requireNonNull(systemUpdate, "systemUpdate");
    }

    private VirtualPrivilegedServicesProfileSnapshot(Parcel in) {
        this(in.readLong(), in.readLong(),
                in.readParcelable(VirtualSearchProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualStorageStatsProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualGraphicsStatsProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualContextHubProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualPersistentDataBlockProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualSystemUpdateProfileSnapshot.class.getClassLoader()));
    }

    public long policyVersion() { return policyVersion; }
    public long updatedAtMs() { return updatedAtMs; }
    public VirtualSearchProfileSnapshot search() { return search; }
    public VirtualStorageStatsProfileSnapshot storageStats() { return storageStats; }
    public VirtualGraphicsStatsProfileSnapshot graphicsStats() { return graphicsStats; }
    public VirtualContextHubProfileSnapshot contextHub() { return contextHub; }
    public VirtualPersistentDataBlockProfileSnapshot persistentDataBlock() { return persistentDataBlock; }
    public VirtualSystemUpdateProfileSnapshot systemUpdate() { return systemUpdate; }

    public VirtualPrivilegedServicesProfileSnapshot withVersion(long version, long updatedAt) {
        return new VirtualPrivilegedServicesProfileSnapshot(version, updatedAt, search, storageStats,
                graphicsStats, contextHub, persistentDataBlock, systemUpdate);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(policyVersion);
        out.writeLong(updatedAtMs);
        out.writeParcelable(search, flags);
        out.writeParcelable(storageStats, flags);
        out.writeParcelable(graphicsStats, flags);
        out.writeParcelable(contextHub, flags);
        out.writeParcelable(persistentDataBlock, flags);
        out.writeParcelable(systemUpdate, flags);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualPrivilegedServicesProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualPrivilegedServicesProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualPrivilegedServicesProfileSnapshot(in);
        }
        @Override public VirtualPrivilegedServicesProfileSnapshot[] newArray(int size) {
            return new VirtualPrivilegedServicesProfileSnapshot[size];
        }
    };
}
