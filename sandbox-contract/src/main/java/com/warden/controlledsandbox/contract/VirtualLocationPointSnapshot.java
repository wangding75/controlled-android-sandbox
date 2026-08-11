package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** One bounded point in a virtual location trajectory. */
public final class VirtualLocationPointSnapshot implements Parcelable {
    private final long offsetMs;
    private final double latitude;
    private final double longitude;
    private final double altitudeMeters;
    private final float accuracyMeters;
    private final float speedMetersPerSecond;
    private final float bearingDegrees;

    public VirtualLocationPointSnapshot(long offsetMs, double latitude, double longitude,
            double altitudeMeters, float accuracyMeters, float speedMetersPerSecond,
            float bearingDegrees) {
        if (offsetMs < 0L || offsetMs > 7L * 24L * 60L * 60L * 1000L) {
            throw new IllegalArgumentException("offsetMs is invalid");
        }
        if (!Double.isFinite(latitude) || latitude < -90d || latitude > 90d) {
            throw new IllegalArgumentException("latitude is invalid");
        }
        if (!Double.isFinite(longitude) || longitude < -180d || longitude > 180d) {
            throw new IllegalArgumentException("longitude is invalid");
        }
        if (!Double.isFinite(altitudeMeters) || altitudeMeters < -20_000d
                || altitudeMeters > 100_000d) {
            throw new IllegalArgumentException("altitudeMeters is invalid");
        }
        if (!Float.isFinite(accuracyMeters) || accuracyMeters < 0f || accuracyMeters > 100_000f) {
            throw new IllegalArgumentException("accuracyMeters is invalid");
        }
        if (!Float.isFinite(speedMetersPerSecond) || speedMetersPerSecond < 0f
                || speedMetersPerSecond > 20_000f) {
            throw new IllegalArgumentException("speedMetersPerSecond is invalid");
        }
        if (!Float.isFinite(bearingDegrees) || bearingDegrees < 0f || bearingDegrees >= 360f) {
            throw new IllegalArgumentException("bearingDegrees is invalid");
        }
        this.offsetMs = offsetMs;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.accuracyMeters = accuracyMeters;
        this.speedMetersPerSecond = speedMetersPerSecond;
        this.bearingDegrees = bearingDegrees;
    }

    private VirtualLocationPointSnapshot(Parcel in) {
        this(in.readLong(), Double.longBitsToDouble(in.readLong()),
                Double.longBitsToDouble(in.readLong()), Double.longBitsToDouble(in.readLong()),
                Float.intBitsToFloat(in.readInt()), Float.intBitsToFloat(in.readInt()),
                Float.intBitsToFloat(in.readInt()));
    }

    public long offsetMs() { return offsetMs; }
    public double latitude() { return latitude; }
    public double longitude() { return longitude; }
    public double altitudeMeters() { return altitudeMeters; }
    public float accuracyMeters() { return accuracyMeters; }
    public float speedMetersPerSecond() { return speedMetersPerSecond; }
    public float bearingDegrees() { return bearingDegrees; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(offsetMs);
        out.writeLong(Double.doubleToLongBits(latitude));
        out.writeLong(Double.doubleToLongBits(longitude));
        out.writeLong(Double.doubleToLongBits(altitudeMeters));
        out.writeInt(Float.floatToIntBits(accuracyMeters));
        out.writeInt(Float.floatToIntBits(speedMetersPerSecond));
        out.writeInt(Float.floatToIntBits(bearingDegrees));
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualLocationPointSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualLocationPointSnapshot createFromParcel(Parcel in) {
            return new VirtualLocationPointSnapshot(in);
        }
        @Override public VirtualLocationPointSnapshot[] newArray(int size) {
            return new VirtualLocationPointSnapshot[size];
        }
    };
}
