package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
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
    private final String enabledSetting;
    private final ArrayList<String> actions;
    private final ArrayList<VirtualIntentFilterSnapshot> intentFilters;

    public VirtualComponentSnapshot(String type, String className, String processName,
                                    boolean exported, boolean enabled, boolean isolated,
                                    String authority, String permission, List<String> actions) {
        this(type, className, processName, exported, enabled, isolated, authority, permission,
                "DEFAULT", actions, List.of());
    }

    public VirtualComponentSnapshot(String type, String className, String processName,
                                    boolean exported, boolean enabled, boolean isolated,
                                    String authority, String permission, String enabledSetting,
                                    List<String> actions,
                                    List<VirtualIntentFilterSnapshot> intentFilters) {
        this.type = componentType(type);
        this.className = required(className, "className");
        this.processName = value(processName);
        this.exported = exported;
        this.enabled = enabled;
        this.isolated = isolated;
        this.authority = value(authority);
        this.permission = value(permission);
        this.enabledSetting = enabledSetting(enabledSetting);
        this.intentFilters = new ArrayList<>(intentFilters == null ? List.of() : intentFilters);
        if (this.intentFilters.size() > 256) throw new IllegalArgumentException("Too many intent filters");
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (actions != null) merged.addAll(actions);
        for (VirtualIntentFilterSnapshot filter : this.intentFilters) merged.addAll(filter.actions());
        this.actions = new ArrayList<>(merged);
    }

    private VirtualComponentSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readInt() != 0,
                in.readInt() != 0, in.readInt() != 0, in.readString(), in.readString(),
                in.readString(), in.createStringArrayList(),
                in.createTypedArrayList(VirtualIntentFilterSnapshot.CREATOR));
    }

    public String type() { return type; }
    public String className() { return className; }
    public String processName() { return processName; }
    public boolean exported() { return exported; }
    public boolean enabled() { return enabled; }
    public boolean isolated() { return isolated; }
    public String authority() { return authority; }
    public String permission() { return permission; }
    public String enabledSetting() { return enabledSetting; }
    public List<String> actions() { return Collections.unmodifiableList(actions); }
    public List<VirtualIntentFilterSnapshot> intentFilters() {
        return Collections.unmodifiableList(intentFilters);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(type); out.writeString(className); out.writeString(processName);
        out.writeInt(exported ? 1 : 0); out.writeInt(enabled ? 1 : 0); out.writeInt(isolated ? 1 : 0);
        out.writeString(authority); out.writeString(permission); out.writeString(enabledSetting);
        out.writeStringList(actions); out.writeTypedList(intentFilters);
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
        if (!List.of("ACTIVITY", "SERVICE", "RECEIVER", "PROVIDER").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported component type: " + value);
        }
        return normalized;
    }
    private static String enabledSetting(String value) {
        String normalized = value == null ? "DEFAULT" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("DEFAULT", "ENABLED", "DISABLED").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported enabled setting: " + value);
        }
        return normalized;
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
    private static String value(String value) { return value == null ? "" : value; }
}
