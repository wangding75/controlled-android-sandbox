package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Typed component metadata supplied by the Binder-owned package authority. */
public final class VirtualComponentSnapshot implements Parcelable {
    private final String type;
    private final String className;
    private final String processName;
    private final boolean exported;
    private final boolean enabled;
    private final boolean isolated;
    private final String authority;
    private final String permission;
    private final ArrayList<String> actions;

    public VirtualComponentSnapshot(String type, String className, String processName,
                                    boolean exported, boolean enabled, boolean isolated,
                                    String authority, String permission, List<String> actions) {
        this.type = componentType(type);
        this.className = required(className, "className");
        this.processName = value(processName);
        this.exported = exported;
        this.enabled = enabled;
        this.isolated = isolated;
        this.authority = value(authority);
        this.permission = value(permission);
        this.actions = new ArrayList<>(actions == null ? List.of() : actions);
    }

    private VirtualComponentSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readInt() != 0,
                in.readInt() != 0, in.readInt() != 0, in.readString(), in.readString(),
                in.createStringArrayList());
    }

    public String type() { return type; }
    public String className() { return className; }
    public String processName() { return processName; }
    public boolean exported() { return exported; }
    public boolean enabled() { return enabled; }
    public boolean isolated() { return isolated; }
    public String authority() { return authority; }
    public String permission() { return permission; }
    public List<String> actions() { return Collections.unmodifiableList(actions); }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(type); out.writeString(className); out.writeString(processName);
        out.writeInt(exported ? 1 : 0); out.writeInt(enabled ? 1 : 0); out.writeInt(isolated ? 1 : 0);
        out.writeString(authority); out.writeString(permission); out.writeStringList(actions);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualComponentSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualComponentSnapshot createFromParcel(Parcel in) {
            return new VirtualComponentSnapshot(in);
        }
        @Override public VirtualComponentSnapshot[] newArray(int size) {
            return new VirtualComponentSnapshot[size];
        }
    };

    private static String componentType(String value) {
        String normalized = required(value, "type").toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("ACTIVITY", "SERVICE", "RECEIVER", "PROVIDER").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported component type: " + value);
        }
        return normalized;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
    private static String value(String value) { return value == null ? "" : value.trim(); }
}
