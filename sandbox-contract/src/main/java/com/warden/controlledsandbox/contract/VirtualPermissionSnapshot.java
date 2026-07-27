package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Declared permission plus virtual decision and host capability state for one sandbox instance. */
public final class VirtualPermissionSnapshot implements Parcelable {
    private final String name;
    private final String decision;
    private final boolean effectiveGranted;
    private final boolean hostDeclared;
    private final boolean hostGranted;
    private final boolean runtimeRequestable;
    private final String appOpName;
    private final String requestState;

    /** Compatibility constructor for older snapshots without host-capability metadata. */
    public VirtualPermissionSnapshot(String name, String decision, boolean effectiveGranted) {
        this(name, decision, effectiveGranted, true, effectiveGranted, false, "", "NONE");
    }

    public VirtualPermissionSnapshot(String name, String decision, boolean effectiveGranted,
                                     boolean hostDeclared, boolean hostGranted,
                                     boolean runtimeRequestable, String appOpName,
                                     String requestState) {
        this.name = required(name, "name");
        this.decision = permissionDecision(decision);
        if ("DENIED".equals(this.decision) && effectiveGranted) {
            throw new IllegalArgumentException("denied permission cannot be effectively granted");
        }
        if (effectiveGranted && (!hostDeclared || !hostGranted)) {
            throw new IllegalArgumentException("effective grant requires host capability");
        }
        if (runtimeRequestable && (!hostDeclared || hostGranted)) {
            throw new IllegalArgumentException("runtimeRequestable contradicts host capability");
        }
        this.effectiveGranted = effectiveGranted;
        this.hostDeclared = hostDeclared;
        this.hostGranted = hostGranted;
        this.runtimeRequestable = runtimeRequestable;
        this.appOpName = optionalName(appOpName, "appOpName");
        this.requestState = requestState(requestState);
    }

    private VirtualPermissionSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readInt() != 0,
                in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.readString(), in.readString());
    }

    public String name() { return name; }
    public String decision() { return decision; }
    public boolean effectiveGranted() { return effectiveGranted; }
    public boolean hostDeclared() { return hostDeclared; }
    public boolean hostGranted() { return hostGranted; }
    public boolean runtimeRequestable() { return runtimeRequestable; }
    public String appOpName() { return appOpName; }
    public String requestState() { return requestState; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(name); out.writeString(decision); out.writeInt(effectiveGranted ? 1 : 0);
        out.writeInt(hostDeclared ? 1 : 0); out.writeInt(hostGranted ? 1 : 0);
        out.writeInt(runtimeRequestable ? 1 : 0); out.writeString(appOpName);
        out.writeString(requestState);
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
    private static String requestState(String value) {
        String normalized = value == null || value.trim().isEmpty()
                ? "NONE" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("NONE", "PENDING", "GRANTED", "DENIED", "CANCELLED").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported permission request state: " + value);
        }
        return normalized;
    }
    private static String optionalName(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isEmpty() && (normalized.length() > 180
                || !normalized.matches("[A-Za-z0-9_.:-]+"))) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
        return normalized;
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
