package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed key-value metadata entry for component-level manifest declarations. */
public final class VirtualComponentMetadataSnapshot implements Parcelable {
    public static final String TYPE_STRING = "STRING";
    public static final String TYPE_INTEGER = "INTEGER";
    public static final String TYPE_BOOLEAN = "BOOLEAN";
    public static final String TYPE_FLOAT = "FLOAT";
    public static final String TYPE_RESOURCE = "RESOURCE";

    private final String name;
    private final String type;
    private final String stringValue;
    private final int intValue;
    private final boolean booleanValue;
    private final float floatValue;
    private final int resourceId;

    public VirtualComponentMetadataSnapshot(String name, String stringValue) {
        this(name, TYPE_STRING, stringValue, 0, false, 0.0f, 0);
    }

    public VirtualComponentMetadataSnapshot(String name, int intValue) {
        this(name, TYPE_INTEGER, null, intValue, false, 0.0f, 0);
    }

    public VirtualComponentMetadataSnapshot(String name, boolean booleanValue) {
        this(name, TYPE_BOOLEAN, null, 0, booleanValue, 0.0f, 0);
    }

    public VirtualComponentMetadataSnapshot(String name, float floatValue) {
        this(name, TYPE_FLOAT, null, 0, false, floatValue, 0);
    }

    public static VirtualComponentMetadataSnapshot forResource(String name, int resourceId) {
        return new VirtualComponentMetadataSnapshot(name, TYPE_RESOURCE, null, 0, false, 0.0f, resourceId);
    }

    public VirtualComponentMetadataSnapshot(String name, String type, String stringValue,
                                           int intValue, boolean booleanValue, float floatValue,
                                           int resourceId) {
        this.name = ContractChecks.requiredText(name, "metadataName", 256);
        this.type = ContractChecks.requiredText(type, "metadataType", 32);
        this.stringValue = stringValue;
        this.intValue = intValue;
        this.booleanValue = booleanValue;
        this.floatValue = floatValue;
        this.resourceId = resourceId;
    }

    private VirtualComponentMetadataSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readInt(),
                in.readInt() != 0, Float.intBitsToFloat(in.readInt()), in.readInt());
    }

    public String name() { return name; }
    public String type() { return type; }
    public String stringValue() { return stringValue; }
    public int intValue() { return intValue; }
    public boolean booleanValue() { return booleanValue; }
    public float floatValue() { return floatValue; }
    public int resourceId() { return resourceId; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(name);
        out.writeString(type);
        out.writeString(stringValue);
        out.writeInt(intValue);
        out.writeInt(booleanValue ? 1 : 0);
        out.writeInt(Float.floatToIntBits(floatValue));
        out.writeInt(resourceId);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualComponentMetadataSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualComponentMetadataSnapshot createFromParcel(Parcel in) {
            return new VirtualComponentMetadataSnapshot(in);
        }
        @Override public VirtualComponentMetadataSnapshot[] newArray(int size) {
            return new VirtualComponentMetadataSnapshot[size];
        }
    };
}
