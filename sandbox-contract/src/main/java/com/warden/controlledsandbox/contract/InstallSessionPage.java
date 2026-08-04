package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Bounded immutable Binder page for persisted install-session metadata. */
public final class InstallSessionPage implements Parcelable, VirtualPageView<InstallSessionInfoSnapshot> {
    private final List<InstallSessionInfoSnapshot> items;
    private final String nextPageToken;
    private final long snapshotRevision;

    public InstallSessionPage(List<InstallSessionInfoSnapshot> items,
            String nextPageToken, long snapshotRevision) {
        this.items = List.copyOf(items == null ? List.of() : items);
        this.nextPageToken = nextPageToken == null ? "" : nextPageToken;
        if (this.nextPageToken.length() > 4096) {
            throw new IllegalArgumentException("PAGE_TOKEN_TOO_LONG");
        }
        this.snapshotRevision = ContractChecks.nonNegative(snapshotRevision, "snapshotRevision");
    }

    private InstallSessionPage(Parcel in) {
        this(in.createTypedArrayList(InstallSessionInfoSnapshot.CREATOR),
                in.readString(), in.readLong());
    }

    @Override public List<InstallSessionInfoSnapshot> items() { return items; }
    @Override public List<VirtualPageBlob> blobs() { return List.of(); }
    @Override public String nextPageToken() { return nextPageToken; }
    @Override public long snapshotRevision() { return snapshotRevision; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeTypedList(items);
        out.writeString(nextPageToken);
        out.writeLong(snapshotRevision);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<InstallSessionPage> CREATOR = new Creator<>() {
        @Override public InstallSessionPage createFromParcel(Parcel in) {
            return new InstallSessionPage(in);
        }
        @Override public InstallSessionPage[] newArray(int size) {
            return new InstallSessionPage[size];
        }
    };
}
