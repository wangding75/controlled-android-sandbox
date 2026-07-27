package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** One virtual AppOps override for a sandbox instance. */
public final class PackageAppOpSnapshot implements Parcelable {
    private final String opName;
    private final String mode;

    public PackageAppOpSnapshot(String opName, String mode) {
        this.opName = required(opName, "opName");
        this.mode = appOpMode(mode);
    }

    private PackageAppOpSnapshot(Parcel in) {
        this(in.readString(), in.readString());
    }

    public String opName() { return opName; }
    public String mode() { return mode; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(opName); out.writeString(mode);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<PackageAppOpSnapshot> CREATOR = new Creator<>() {
        @Override public PackageAppOpSnapshot createFromParcel(Parcel in) {
            return new PackageAppOpSnapshot(in);
        }
        @Override public PackageAppOpSnapshot[] newArray(int size) {
            return new PackageAppOpSnapshot[size];
        }
    };

    private static String appOpMode(String value) {
        String normalized = required(value, "mode").toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("DEFAULT", "ALLOWED", "IGNORED", "ERRORED").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported AppOps mode: " + value);
        }
        return normalized;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
