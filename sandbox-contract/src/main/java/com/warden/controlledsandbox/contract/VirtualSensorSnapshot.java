package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** One virtual sensor descriptor and its deterministic sample values. */
public final class VirtualSensorSnapshot implements Parcelable {
    private final int handle;
    private final int type;
    private final String name;
    private final String vendor;
    private final int version;
    private final float maximumRange;
    private final float resolution;
    private final float powerMilliamp;
    private final int minimumDelayUs;
    private final int maximumDelayUs;
    private final int reportingMode;
    private final boolean wakeUp;
    private final boolean dynamic;
    private final float[] values;
    private final int accuracy;

    public VirtualSensorSnapshot(int handle, int type, String name, String vendor, int version,
            float maximumRange, float resolution, float powerMilliamp, int minimumDelayUs,
            int maximumDelayUs, int reportingMode, boolean wakeUp, boolean dynamic,
            float[] values, int accuracy) {
        if (handle < 0 || type <= 0 || version < 0) {
            throw new IllegalArgumentException("sensor identity is invalid");
        }
        this.handle = handle;
        this.type = type;
        this.name = ContractChecks.requiredText(name, "sensorName", 96).trim();
        this.vendor = ContractChecks.optionalText(vendor, "sensorVendor", 96).trim();
        this.version = version;
        this.maximumRange = finiteNonNegative(maximumRange, "maximumRange");
        this.resolution = finiteNonNegative(resolution, "resolution");
        this.powerMilliamp = finiteNonNegative(powerMilliamp, "powerMilliamp");
        if (minimumDelayUs < 0 || maximumDelayUs < 0 || maximumDelayUs < minimumDelayUs) {
            throw new IllegalArgumentException("sensor delays are invalid");
        }
        if (reportingMode < 0 || reportingMode > 4) {
            throw new IllegalArgumentException("reportingMode is invalid");
        }
        this.minimumDelayUs = minimumDelayUs;
        this.maximumDelayUs = maximumDelayUs;
        this.reportingMode = reportingMode;
        this.wakeUp = wakeUp;
        this.dynamic = dynamic;
        float[] copy = values == null ? new float[0] : values.clone();
        if (copy.length > 16) throw new IllegalArgumentException("sensor values limit exceeded");
        for (float value : copy) if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("sensor value is not finite");
        }
        this.values = copy;
        if (accuracy < -1 || accuracy > 3) throw new IllegalArgumentException("accuracy is invalid");
        this.accuracy = accuracy;
    }

    private VirtualSensorSnapshot(Parcel in) {
        this(in.readInt(), in.readInt(), in.readString(), in.readString(), in.readInt(),
                Float.intBitsToFloat(in.readInt()), Float.intBitsToFloat(in.readInt()),
                Float.intBitsToFloat(in.readInt()), in.readInt(), in.readInt(), in.readInt(),
                in.readInt() != 0, in.readInt() != 0, readValues(in), in.readInt());
    }

    public int handle() { return handle; }
    public int type() { return type; }
    public String name() { return name; }
    public String vendor() { return vendor; }
    public int version() { return version; }
    public float maximumRange() { return maximumRange; }
    public float resolution() { return resolution; }
    public float powerMilliamp() { return powerMilliamp; }
    public int minimumDelayUs() { return minimumDelayUs; }
    public int maximumDelayUs() { return maximumDelayUs; }
    public int reportingMode() { return reportingMode; }
    public boolean wakeUp() { return wakeUp; }
    public boolean dynamic() { return dynamic; }
    public float[] values() { return values.clone(); }
    public int accuracy() { return accuracy; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(handle); out.writeInt(type); out.writeString(name); out.writeString(vendor);
        out.writeInt(version); out.writeInt(Float.floatToIntBits(maximumRange));
        out.writeInt(Float.floatToIntBits(resolution)); out.writeInt(Float.floatToIntBits(powerMilliamp));
        out.writeInt(minimumDelayUs); out.writeInt(maximumDelayUs); out.writeInt(reportingMode);
        out.writeInt(wakeUp ? 1 : 0); out.writeInt(dynamic ? 1 : 0);
        out.writeInt(values.length); for (float value : values) out.writeInt(Float.floatToIntBits(value));
        out.writeInt(accuracy);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualSensorSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualSensorSnapshot createFromParcel(Parcel in) { return new VirtualSensorSnapshot(in); }
        @Override public VirtualSensorSnapshot[] newArray(int size) { return new VirtualSensorSnapshot[size]; }
    };

    private static float[] readValues(Parcel in) {
        int length = in.readInt();
        if (length < 0 || length > 16) throw new IllegalArgumentException("sensor values limit exceeded");
        float[] values = new float[length];
        for (int index = 0; index < length; index++) values[index] = Float.intBitsToFloat(in.readInt());
        return values;
    }
    private static float finiteNonNegative(float value, String field) {
        if (!Float.isFinite(value) || value < 0f) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
}
