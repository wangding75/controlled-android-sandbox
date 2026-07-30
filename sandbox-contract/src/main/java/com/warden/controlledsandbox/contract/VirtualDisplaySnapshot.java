package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Stable virtual display descriptor. */
public final class VirtualDisplaySnapshot implements Parcelable {
    private final int displayId;
    private final String name;
    private final int widthPixels;
    private final int heightPixels;
    private final int densityDpi;
    private final float xdpi;
    private final float ydpi;
    private final float refreshRate;
    private final int rotation;
    private final int state;
    private final int flags;
    private final boolean secure;

    public VirtualDisplaySnapshot(int displayId, String name, int widthPixels, int heightPixels,
            int densityDpi, float xdpi, float ydpi, float refreshRate, int rotation,
            int state, int flags, boolean secure) {
        this.displayId = ContractChecks.nonNegative(displayId, "displayId");
        this.name = ContractChecks.requiredText(name, "name", 128);
        if (widthPixels < 1 || widthPixels > 16384 || heightPixels < 1 || heightPixels > 16384) {
            throw new IllegalArgumentException("display dimensions are invalid");
        }
        if (densityDpi < 72 || densityDpi > 1280) {
            throw new IllegalArgumentException("densityDpi is invalid");
        }
        if (!Float.isFinite(xdpi) || xdpi <= 0f || !Float.isFinite(ydpi) || ydpi <= 0f
                || !Float.isFinite(refreshRate) || refreshRate < 1f || refreshRate > 1000f) {
            throw new IllegalArgumentException("display metrics are invalid");
        }
        if (rotation < 0 || rotation > 3 || state < 0) {
            throw new IllegalArgumentException("display rotation/state is invalid");
        }
        this.widthPixels = widthPixels; this.heightPixels = heightPixels;
        this.densityDpi = densityDpi; this.xdpi = xdpi; this.ydpi = ydpi;
        this.refreshRate = refreshRate; this.rotation = rotation; this.state = state;
        this.flags = flags; this.secure = secure;
    }

    private VirtualDisplaySnapshot(Parcel in) {
        this(in.readInt(), in.readString(), in.readInt(), in.readInt(), in.readInt(),
                Float.intBitsToFloat(in.readInt()), Float.intBitsToFloat(in.readInt()),
                Float.intBitsToFloat(in.readInt()), in.readInt(), in.readInt(), in.readInt(),
                in.readInt() != 0);
    }

    public int displayId() { return displayId; }
    public String name() { return name; }
    public int widthPixels() { return widthPixels; }
    public int heightPixels() { return heightPixels; }
    public int densityDpi() { return densityDpi; }
    public float xdpi() { return xdpi; }
    public float ydpi() { return ydpi; }
    public float refreshRate() { return refreshRate; }
    public int rotation() { return rotation; }
    public int state() { return state; }
    public int flags() { return flags; }
    public boolean secure() { return secure; }

    @Override public void writeToParcel(Parcel out, int parcelFlags) {
        out.writeInt(displayId); out.writeString(name); out.writeInt(widthPixels);
        out.writeInt(heightPixels); out.writeInt(densityDpi);
        out.writeInt(Float.floatToIntBits(xdpi)); out.writeInt(Float.floatToIntBits(ydpi));
        out.writeInt(Float.floatToIntBits(refreshRate)); out.writeInt(rotation);
        out.writeInt(state); out.writeInt(flags); out.writeInt(secure ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualDisplaySnapshot> CREATOR = new Creator<>() {
        @Override public VirtualDisplaySnapshot createFromParcel(Parcel in) {
            return new VirtualDisplaySnapshot(in);
        }
        @Override public VirtualDisplaySnapshot[] newArray(int size) {
            return new VirtualDisplaySnapshot[size];
        }
    };
}
