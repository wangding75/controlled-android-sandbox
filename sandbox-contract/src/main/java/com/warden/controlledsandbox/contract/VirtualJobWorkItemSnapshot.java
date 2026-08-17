package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Version-neutral, bounded projection of one Android JobWorkItem.
 *
 * <p>The Intent and PersistableBundle are carried as Parcelable payloads so the
 * host JobService never hands its framework object directly to Guest code. The
 * work id remains host-owned and is used only for the completion capability.</p>
 */
public final class VirtualJobWorkItemSnapshot implements Parcelable {
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    private final int workId;
    private final int deliveryCount;
    private final byte[] intent;
    private final byte[] extras;
    private final long estimatedNetworkDownloadBytes;
    private final long estimatedNetworkUploadBytes;
    private final long minimumNetworkChunkBytes;

    public VirtualJobWorkItemSnapshot(int workId, int deliveryCount, byte[] intent, byte[] extras,
            long estimatedNetworkDownloadBytes, long estimatedNetworkUploadBytes,
            long minimumNetworkChunkBytes) {
        if (workId < 0 || deliveryCount < 0) {
            throw new IllegalArgumentException("invalid JobWorkItem identity/counter");
        }
        if (estimatedNetworkDownloadBytes < -1L || estimatedNetworkUploadBytes < -1L
                || minimumNetworkChunkBytes < -1L) {
            throw new IllegalArgumentException("invalid JobWorkItem network estimate");
        }
        this.workId = workId;
        this.deliveryCount = deliveryCount;
        this.intent = payload(intent, "intent");
        this.extras = payload(extras, "extras");
        this.estimatedNetworkDownloadBytes = estimatedNetworkDownloadBytes;
        this.estimatedNetworkUploadBytes = estimatedNetworkUploadBytes;
        this.minimumNetworkChunkBytes = minimumNetworkChunkBytes;
    }

    private VirtualJobWorkItemSnapshot(Parcel in) {
        this(in.readInt(), in.readInt(), in.createByteArray(), in.createByteArray(),
                in.readLong(), in.readLong(), in.readLong());
    }

    public int workId() { return workId; }
    public int deliveryCount() { return deliveryCount; }
    public byte[] intent() { return intent.clone(); }
    public byte[] extras() { return extras.clone(); }
    public long estimatedNetworkDownloadBytes() { return estimatedNetworkDownloadBytes; }
    public long estimatedNetworkUploadBytes() { return estimatedNetworkUploadBytes; }
    public long minimumNetworkChunkBytes() { return minimumNetworkChunkBytes; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(workId);
        out.writeInt(deliveryCount);
        out.writeByteArray(intent);
        out.writeByteArray(extras);
        out.writeLong(estimatedNetworkDownloadBytes);
        out.writeLong(estimatedNetworkUploadBytes);
        out.writeLong(minimumNetworkChunkBytes);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualJobWorkItemSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualJobWorkItemSnapshot createFromParcel(Parcel in) {
            return new VirtualJobWorkItemSnapshot(in);
        }
        @Override public VirtualJobWorkItemSnapshot[] newArray(int size) {
            return new VirtualJobWorkItemSnapshot[size];
        }
    };

    private static byte[] payload(byte[] value, String name) {
        byte[] copy = value == null ? new byte[0] : value.clone();
        if (copy.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(name + " too large");
        }
        return copy;
    }
}
