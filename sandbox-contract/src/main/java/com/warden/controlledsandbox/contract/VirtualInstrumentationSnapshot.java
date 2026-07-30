package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Immutable manifest instrumentation declaration. */
public final class VirtualInstrumentationSnapshot implements Parcelable {
    private final String className;
    private final String targetPackage;
    private final String targetProcesses;
    private final boolean handleProfiling;
    private final boolean functionalTest;
    private final boolean enabled;

    public VirtualInstrumentationSnapshot(String className, String targetPackage,
                                          String targetProcesses, boolean handleProfiling,
                                          boolean functionalTest, boolean enabled) {
        this.className = required(className, "className");
        this.targetPackage = required(targetPackage, "targetPackage");
        this.targetProcesses = value(targetProcesses);
        this.handleProfiling = handleProfiling;
        this.functionalTest = functionalTest;
        this.enabled = enabled;
    }

    private VirtualInstrumentationSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readInt() != 0,
                in.readInt() != 0, in.readInt() != 0);
    }

    public String className() { return className; }
    public String targetPackage() { return targetPackage; }
    public String targetProcesses() { return targetProcesses; }
    public boolean handleProfiling() { return handleProfiling; }
    public boolean functionalTest() { return functionalTest; }
    public boolean enabled() { return enabled; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(className); out.writeString(targetPackage); out.writeString(targetProcesses);
        out.writeInt(handleProfiling ? 1 : 0); out.writeInt(functionalTest ? 1 : 0);
        out.writeInt(enabled ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualInstrumentationSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualInstrumentationSnapshot createFromParcel(Parcel in) {
            return new VirtualInstrumentationSnapshot(in);
        }
        @Override public VirtualInstrumentationSnapshot[] newArray(int size) {
            return new VirtualInstrumentationSnapshot[size];
        }
    };

    private static String required(String value, String name) {
        String normalized = value(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
    private static String value(String value) { return value == null ? "" : value.trim(); }
}
