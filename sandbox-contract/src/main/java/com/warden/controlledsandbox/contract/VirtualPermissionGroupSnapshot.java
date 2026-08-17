package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Immutable custom permission-group declaration carried by the virtual PMS snapshot. */
public final class VirtualPermissionGroupSnapshot implements Parcelable {
    private final String name;
    private final String label;
    private final String description;
    private final int labelRes;
    private final int descriptionRes;
    private final int icon;
    private final int requestRes;
    private final int priority;
    private final int flags;

    public VirtualPermissionGroupSnapshot(String name, String label, String description,
                                          int labelRes, int descriptionRes, int icon,
                                          int requestRes, int priority, int flags) {
        this.name = required(name, "name");
        this.label = optional(label);
        this.description = optional(description);
        this.labelRes = nonNegative(labelRes, "labelRes");
        this.descriptionRes = nonNegative(descriptionRes, "descriptionRes");
        this.icon = nonNegative(icon, "icon");
        this.requestRes = nonNegative(requestRes, "requestRes");
        this.priority = priority;
        this.flags = nonNegative(flags, "flags");
    }

    private VirtualPermissionGroupSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readInt(), in.readInt(),
                in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }

    public String name() { return name; }
    public String label() { return label; }
    public String description() { return description; }
    public int labelRes() { return labelRes; }
    public int descriptionRes() { return descriptionRes; }
    public int icon() { return icon; }
    public int requestRes() { return requestRes; }
    public int priority() { return priority; }
    public int flags() { return flags; }

    @Override public void writeToParcel(Parcel out, int parcelFlags) {
        out.writeString(name); out.writeString(label); out.writeString(description);
        out.writeInt(labelRes); out.writeInt(descriptionRes); out.writeInt(icon);
        out.writeInt(requestRes); out.writeInt(priority); out.writeInt(flags);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualPermissionGroupSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualPermissionGroupSnapshot createFromParcel(Parcel in) {
            return new VirtualPermissionGroupSnapshot(in);
        }
        @Override public VirtualPermissionGroupSnapshot[] newArray(int size) {
            return new VirtualPermissionGroupSnapshot[size];
        }
    };

    private static String required(String value, String field) {
        String normalized = optional(value);
        if (normalized.isEmpty() || normalized.length() > 180
                || !normalized.matches("[A-Za-z0-9_.:]+")) {
            throw new IllegalArgumentException("Invalid " + field + ": " + value);
        }
        return normalized;
    }
    private static String optional(String value) { return value == null ? "" : value.trim(); }
    private static int nonNegative(int value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }
}
