package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Declared permission plus virtual grant decision for one sandbox instance. */
public final class VirtualPermissionSnapshot implements Parcelable {
    private final String name;
    private final String decision;
    private final boolean effectiveGranted;

    public VirtualPermissionSnapshot(String name, String decision, boolean effectiveGranted) {
        this.name = required(name, "name");
        this.decision = permissionDecision(decision);
        boolean expected = !"DENIED".equals(this.decision);
        if (effectiveGranted != expected) {
            throw new IllegalArgumentException("effectiveGranted contradicts permission decision");
        }
        this.effectiveGranted = expected;
    }

    private VirtualPermissionSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readInt() != 0);
    }

    public String name() { return name; }
    public String decision() { return decision; }
    public boolean effectiveGranted() { return effectiveGranted; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(name); out.writeString(decision); out.writeInt(effectiveGranted ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualPermissionSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualPermissionSnapshot createFromParcel(Parcel in) {
            return new VirtualPermissionSnapshot(in);
        }
        @Override public VirtualPermissionSnapshot[] newArray(int size) {
            return new VirtualPermissionSnapshot[size];
        }
    };

    private static String permissionDecision(String value) {
        String normalized = required(value, "decision").toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("DEFAULT", "GRANTED", "DENIED").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported permission decision: " + value);
        }
        return normalized;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
