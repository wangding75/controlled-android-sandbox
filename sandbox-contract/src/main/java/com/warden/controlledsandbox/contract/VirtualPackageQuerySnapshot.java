package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** One explicit package-visibility declaration from an APK's {@code <queries>} block. */
public final class VirtualPackageQuerySnapshot implements Parcelable {
    public static final String PACKAGE = "PACKAGE";
    public static final String PROVIDER = "PROVIDER";
    public static final String INTENT = "INTENT";

    private final String kind;
    private final String value;
    private final VirtualIntentFilterSnapshot intent;

    public VirtualPackageQuerySnapshot(String kind, String value,
                                       VirtualIntentFilterSnapshot intent) {
        if (!PACKAGE.equals(kind) && !PROVIDER.equals(kind) && !INTENT.equals(kind)) {
            throw new IllegalArgumentException("Unsupported package query kind: " + kind);
        }
        this.kind = kind;
        this.value = value == null ? "" : value.trim();
        this.intent = intent;
        if (PACKAGE.equals(kind) && this.value.isEmpty()) {
            throw new IllegalArgumentException("Package query target is required");
        }
        if (PROVIDER.equals(kind) && this.value.isEmpty()) {
            throw new IllegalArgumentException("Provider query authority is required");
        }
        if (INTENT.equals(kind) && intent == null) {
            throw new IllegalArgumentException("Intent query filter is required");
        }
    }

    private VirtualPackageQuerySnapshot(Parcel in) {
        this(in.readString(), in.readString(),
                in.readParcelable(VirtualIntentFilterSnapshot.class.getClassLoader()));
    }

    public String kind() { return kind; }
    public String value() { return value; }
    public VirtualIntentFilterSnapshot intent() { return intent; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(kind); out.writeString(value); out.writeParcelable(intent, flags);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualPackageQuerySnapshot> CREATOR = new Creator<>() {
        @Override public VirtualPackageQuerySnapshot createFromParcel(Parcel in) {
            return new VirtualPackageQuerySnapshot(in);
        }
        @Override public VirtualPackageQuerySnapshot[] newArray(int size) {
            return new VirtualPackageQuerySnapshot[size];
        }
    };
}
