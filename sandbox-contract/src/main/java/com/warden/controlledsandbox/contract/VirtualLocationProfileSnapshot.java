package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Locale;

/** Typed virtual location policy for one package and virtual user. */
public final class VirtualLocationProfileSnapshot implements Parcelable {
    public static final String MODE_BLOCKED = "BLOCKED";
    public static final String MODE_STATIC = "STATIC";
    public static final String MODE_HOST = "HOST";

    private final String mode;
    private final String provider;
    private final boolean providerEnabled;
    private final double latitude;
    private final double longitude;
    private final double altitudeMeters;
    private final float accuracyMeters;
    private final float speedMetersPerSecond;
    private final float bearingDegrees;
    private final long timeMs;
    private final long elapsedRealtimeNanos;
    private final long minimumUpdateIntervalMs;
    private final boolean gnssEnabled;
    private final int satellitesInView;
    private final int satellitesUsedInFix;
    private final String nmeaSentence;

    public VirtualLocationProfileSnapshot(String mode, String provider, boolean providerEnabled,
            double latitude, double longitude, double altitudeMeters, float accuracyMeters,
            float speedMetersPerSecond, float bearingDegrees, long timeMs,
            long elapsedRealtimeNanos, long minimumUpdateIntervalMs, boolean gnssEnabled,
            int satellitesInView, int satellitesUsedInFix, String nmeaSentence) {
        this.mode = mode(mode);
        this.provider = ContractChecks.optionalText(provider, "provider", 64);
        if (MODE_STATIC.equals(this.mode) && this.provider.isEmpty()) {
            throw new IllegalArgumentException("provider is required in STATIC mode");
        }
        this.providerEnabled = providerEnabled;
        if (!Double.isFinite(latitude) || latitude < -90d || latitude > 90d) {
            throw new IllegalArgumentException("latitude is invalid");
        }
        if (!Double.isFinite(longitude) || longitude < -180d || longitude > 180d) {
            throw new IllegalArgumentException("longitude is invalid");
        }
        if (!Double.isFinite(altitudeMeters) || altitudeMeters < -20_000d || altitudeMeters > 100_000d) {
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
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.accuracyMeters = accuracyMeters;
        this.speedMetersPerSecond = speedMetersPerSecond;
        this.bearingDegrees = bearingDegrees;
        this.timeMs = ContractChecks.nonNegative(timeMs, "timeMs");
        this.elapsedRealtimeNanos = ContractChecks.nonNegative(elapsedRealtimeNanos, "elapsedRealtimeNanos");
        this.minimumUpdateIntervalMs = ContractChecks.nonNegative(
                minimumUpdateIntervalMs, "minimumUpdateIntervalMs");
        this.gnssEnabled = gnssEnabled;
        this.satellitesInView = boundedSatellites(satellitesInView, "satellitesInView");
        this.satellitesUsedInFix = boundedSatellites(satellitesUsedInFix, "satellitesUsedInFix");
        if (this.satellitesUsedInFix > this.satellitesInView) {
            throw new IllegalArgumentException("satellitesUsedInFix exceeds satellitesInView");
        }
        this.nmeaSentence = ContractChecks.optionalText(nmeaSentence, "nmeaSentence", 512);
    }

    private VirtualLocationProfileSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readInt() != 0,
                Double.longBitsToDouble(in.readLong()), Double.longBitsToDouble(in.readLong()),
                Double.longBitsToDouble(in.readLong()), Float.intBitsToFloat(in.readInt()),
                Float.intBitsToFloat(in.readInt()), Float.intBitsToFloat(in.readInt()),
                in.readLong(), in.readLong(), in.readLong(), in.readInt() != 0,
                in.readInt(), in.readInt(), in.readString());
    }

    public String mode() { return mode; }
    public String provider() { return provider; }
    public boolean providerEnabled() { return providerEnabled; }
    public double latitude() { return latitude; }
    public double longitude() { return longitude; }
    public double altitudeMeters() { return altitudeMeters; }
    public float accuracyMeters() { return accuracyMeters; }
    public float speedMetersPerSecond() { return speedMetersPerSecond; }
    public float bearingDegrees() { return bearingDegrees; }
    public long timeMs() { return timeMs; }
    public long elapsedRealtimeNanos() { return elapsedRealtimeNanos; }
    public long minimumUpdateIntervalMs() { return minimumUpdateIntervalMs; }
    public boolean gnssEnabled() { return gnssEnabled; }
    public int satellitesInView() { return satellitesInView; }
    public int satellitesUsedInFix() { return satellitesUsedInFix; }
    public String nmeaSentence() { return nmeaSentence; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeString(provider); out.writeInt(providerEnabled ? 1 : 0);
        out.writeLong(Double.doubleToLongBits(latitude)); out.writeLong(Double.doubleToLongBits(longitude));
        out.writeLong(Double.doubleToLongBits(altitudeMeters));
        out.writeInt(Float.floatToIntBits(accuracyMeters)); out.writeInt(Float.floatToIntBits(speedMetersPerSecond));
        out.writeInt(Float.floatToIntBits(bearingDegrees)); out.writeLong(timeMs);
        out.writeLong(elapsedRealtimeNanos); out.writeLong(minimumUpdateIntervalMs);
        out.writeInt(gnssEnabled ? 1 : 0); out.writeInt(satellitesInView);
        out.writeInt(satellitesUsedInFix); out.writeString(nmeaSentence);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualLocationProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualLocationProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualLocationProfileSnapshot(in);
        }
        @Override public VirtualLocationProfileSnapshot[] newArray(int size) {
            return new VirtualLocationProfileSnapshot[size];
        }
    };

    static String mode(String value) {
        String normalized = ContractChecks.requiredText(value, "mode", 16).toUpperCase(Locale.ROOT);
        if (!MODE_BLOCKED.equals(normalized) && !MODE_STATIC.equals(normalized)
                && !MODE_HOST.equals(normalized)) {
            throw new IllegalArgumentException("unsupported virtual service mode: " + value);
        }
        return normalized;
    }
    private static int boundedSatellites(int value, String field) {
        if (value < 0 || value > 64) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
}
