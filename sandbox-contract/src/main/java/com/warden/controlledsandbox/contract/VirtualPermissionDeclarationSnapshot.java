package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Immutable custom permission declaration carried by the virtual PMS package snapshot. */
public final class VirtualPermissionDeclarationSnapshot implements Parcelable {
    private final String name;
    private final String group;
    private final String label;
    private final String description;
    private final int labelRes;
    private final int descriptionRes;
    private final int icon;
    private final int protectionLevel;
    private final int flags;
    private final boolean tree;

    public VirtualPermissionDeclarationSnapshot(String name, String group, String label,
                                                String description, int labelRes,
                                                int descriptionRes, int icon,
                                                int protectionLevel, int flags) {
        this(name, group, label, description, labelRes, descriptionRes, icon,
                protectionLevel, flags, false);
    }

    public VirtualPermissionDeclarationSnapshot(String name, String group, String label,
                                                String description, int labelRes,
                                                int descriptionRes, int icon,
                                                int protectionLevel, int flags, boolean tree) {
        this.name = required(name, "name");
        this.group = optional(group);
        this.label = optional(label);
        this.description = optional(description);
        this.labelRes = nonNegative(labelRes, "labelRes");
        this.descriptionRes = nonNegative(descriptionRes, "descriptionRes");
        this.icon = nonNegative(icon, "icon");
        this.protectionLevel = nonNegative(protectionLevel, "protectionLevel");
        this.flags = nonNegative(flags, "flags");
        this.tree = tree;
    }

    private VirtualPermissionDeclarationSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readString(),
                in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                in.readInt() != 0);
    }

    public String name() { return name; }
    public String group() { return group; }
    public String label() { return label; }
    public String description() { return description; }
    public int labelRes() { return labelRes; }
    public int descriptionRes() { return descriptionRes; }
    public int icon() { return icon; }
    public int protectionLevel() { return protectionLevel; }
    public int flags() { return flags; }
    public boolean tree() { return tree; }

    @Override public void writeToParcel(Parcel out, int parcelFlags) {
        out.writeString(name); out.writeString(group); out.writeString(label);
        out.writeString(description); out.writeInt(labelRes); out.writeInt(descriptionRes);
        out.writeInt(icon); out.writeInt(protectionLevel); out.writeInt(flags);
        out.writeInt(tree ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualPermissionDeclarationSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualPermissionDeclarationSnapshot createFromParcel(Parcel in) {
            return new VirtualPermissionDeclarationSnapshot(in);
        }
        @Override public VirtualPermissionDeclarationSnapshot[] newArray(int size) {
            return new VirtualPermissionDeclarationSnapshot[size];
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
