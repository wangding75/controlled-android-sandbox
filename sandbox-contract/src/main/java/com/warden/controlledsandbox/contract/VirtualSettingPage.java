package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.List;

/** Typed immutable Binder page for VirtualSettingSnapshot. */
public final class VirtualSettingPage implements Parcelable, VirtualPageView<VirtualSettingSnapshot> {
    private final List<VirtualSettingSnapshot> items;
    private final List<VirtualPageBlob> blobs;
    private final String nextPageToken;
    private final long snapshotRevision;

    public VirtualSettingPage(List<VirtualSettingSnapshot> items, List<VirtualPageBlob> blobs,
            String nextPageToken, long snapshotRevision) {
        this.items = List.copyOf(items == null ? List.of() : items);
        this.blobs = List.copyOf(blobs == null ? List.of() : blobs);
        this.nextPageToken = nextPageToken == null ? "" : nextPageToken;
        if (this.nextPageToken.length() > 4096) throw new IllegalArgumentException("PAGE_TOKEN_TOO_LONG");
        this.snapshotRevision = ContractChecks.nonNegative(snapshotRevision, "snapshotRevision");
    }
    private VirtualSettingPage(Parcel in) {
        this(in.createTypedArrayList(VirtualSettingSnapshot.CREATOR),
                in.createTypedArrayList(VirtualPageBlob.CREATOR), in.readString(), in.readLong());
    }
    public List<VirtualSettingSnapshot> items() { return items; }
    public List<VirtualPageBlob> blobs() { return blobs; }
    public String nextPageToken() { return nextPageToken; }
    public long snapshotRevision() { return snapshotRevision; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeTypedList(items); out.writeTypedList(blobs); out.writeString(nextPageToken); out.writeLong(snapshotRevision);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualSettingPage> CREATOR = new Creator<>() {
        @Override public VirtualSettingPage createFromParcel(Parcel in) { return new VirtualSettingPage(in); }
        @Override public VirtualSettingPage[] newArray(int size) { return new VirtualSettingPage[size]; }
    };
}
