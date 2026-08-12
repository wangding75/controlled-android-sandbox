package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Typed virtual location policy for one package and virtual user. */
public final class VirtualLocationProfileSnapshot implements Parcelable {
    public static final String MODE_BLOCKED = "BLOCKED";
    public static final String MODE_STATIC = "STATIC";
    public static final String MODE_HOST = "HOST";
    public static final String TRAJECTORY_FIXED = "FIXED";
    public static final String TRAJECTORY_WALKING = "WALKING";
    public static final String TRAJECTORY_CYCLING = "CYCLING";
    public static final String TRAJECTORY_DRIVING = "DRIVING";
    public static final String TRAJECTORY_ROUTE = "ROUTE";
    public static final String TIME_POLICY_NOW = "NOW";
    public static final String TIME_POLICY_PROFILE = "PROFILE";
    public static final String ELAPSED_POLICY_NOW = "NOW";
    public static final String ELAPSED_POLICY_PROFILE = "PROFILE";

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
    private final String trajectoryMode;
    private final long trajectoryIntervalMs;
    private final List<VirtualLocationPointSnapshot> trajectoryPoints;
    private final String timestampPolicy;
    private final String elapsedRealtimePolicy;

    public VirtualLocationProfileSnapshot(String mode, String provider, boolean providerEnabled,
            double latitude, double longitude, double altitudeMeters, float accuracyMeters,
            float speedMetersPerSecond, float bearingDegrees, long timeMs,
            long elapsedRealtimeNanos, long minimumUpdateIntervalMs, boolean gnssEnabled,
            int satellitesInView, int satellitesUsedInFix, String nmeaSentence) {
        this(mode, provider, providerEnabled, latitude, longitude, altitudeMeters, accuracyMeters,
                speedMetersPerSecond, bearingDegrees, timeMs, elapsedRealtimeNanos,
                minimumUpdateIntervalMs, gnssEnabled, satellitesInView, satellitesUsedInFix,
                nmeaSentence, TRAJECTORY_FIXED, minimumUpdateIntervalMs, List.of(),
                TIME_POLICY_NOW, ELAPSED_POLICY_NOW);
    }

    public VirtualLocationProfileSnapshot(String mode, String provider, boolean providerEnabled,
            double latitude, double longitude, double altitudeMeters, float accuracyMeters,
            float speedMetersPerSecond, float bearingDegrees, long timeMs,
            long elapsedRealtimeNanos, long minimumUpdateIntervalMs, boolean gnssEnabled,
            int satellitesInView, int satellitesUsedInFix, String nmeaSentence,
            String trajectoryMode, long trajectoryIntervalMs,
            List<VirtualLocationPointSnapshot> trajectoryPoints,
            String timestampPolicy, String elapsedRealtimePolicy) {
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
        this.trajectoryMode = trajectory(trajectoryMode);
        this.trajectoryIntervalMs = ContractChecks.nonNegative(trajectoryIntervalMs,
                "trajectoryIntervalMs");
        if (this.trajectoryIntervalMs > 24L * 60L * 60L * 1000L) {
            throw new IllegalArgumentException("trajectoryIntervalMs is invalid");
        }
        List<VirtualLocationPointSnapshot> points = trajectoryPoints == null
                ? List.of() : new ArrayList<>(trajectoryPoints);
        if (points.size() > 512 || points.contains(null)) {
            throw new IllegalArgumentException("trajectoryPoints are invalid");
        }
        long previousOffset = -1L;
        for (VirtualLocationPointSnapshot point : points) {
            if (point.offsetMs() < previousOffset) {
                throw new IllegalArgumentException("trajectoryPoints must be ordered");
            }
            previousOffset = point.offsetMs();
        }
        if (!TRAJECTORY_FIXED.equals(this.trajectoryMode) && points.isEmpty()) {
            throw new IllegalArgumentException("trajectoryPoints are required for moving trajectory");
        }
        this.trajectoryPoints = Collections.unmodifiableList(points);
        this.timestampPolicy = policy(timestampPolicy, TIME_POLICY_NOW, "timestampPolicy");
        this.elapsedRealtimePolicy = policy(elapsedRealtimePolicy, ELAPSED_POLICY_NOW,
                "elapsedRealtimePolicy");
    }

    private VirtualLocationProfileSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readInt() != 0,
                Double.longBitsToDouble(in.readLong()), Double.longBitsToDouble(in.readLong()),
                Double.longBitsToDouble(in.readLong()), Float.intBitsToFloat(in.readInt()),
                Float.intBitsToFloat(in.readInt()), Float.intBitsToFloat(in.readInt()),
                in.readLong(), in.readLong(), in.readLong(), in.readInt() != 0,
                in.readInt(), in.readInt(), in.readString(), in.readString(), in.readLong(),
                in.createTypedArrayList(VirtualLocationPointSnapshot.CREATOR), in.readString(),
                in.readString());
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
    public String trajectoryMode() { return trajectoryMode; }
    public long trajectoryIntervalMs() { return trajectoryIntervalMs; }
    public List<VirtualLocationPointSnapshot> trajectoryPoints() { return trajectoryPoints; }
    public String timestampPolicy() { return timestampPolicy; }
    public String elapsedRealtimePolicy() { return elapsedRealtimePolicy; }

    /** Returns the interpolated point for a monotonic clock reading. */
    public VirtualLocationPointSnapshot pointAt(long nowElapsedRealtimeNanos) {
        if (trajectoryPoints.isEmpty() || TRAJECTORY_FIXED.equals(trajectoryMode)) {
            return new VirtualLocationPointSnapshot(0L, latitude, longitude, altitudeMeters,
                    accuracyMeters, speedMetersPerSecond, bearingDegrees);
        }
        long base = elapsedRealtimeNanos > 0L ? elapsedRealtimeNanos : nowElapsedRealtimeNanos;
        long offset = Math.max(0L, (nowElapsedRealtimeNanos - base) / 1_000_000L);
        VirtualLocationPointSnapshot first = trajectoryPoints.get(0);
        if (offset <= first.offsetMs()) return first;
        for (int index = 1; index < trajectoryPoints.size(); index++) {
            VirtualLocationPointSnapshot next = trajectoryPoints.get(index);
            if (offset <= next.offsetMs()) {
                VirtualLocationPointSnapshot previous = trajectoryPoints.get(index - 1);
                long span = Math.max(1L, next.offsetMs() - previous.offsetMs());
                double fraction = (double) (offset - previous.offsetMs()) / span;
                return interpolate(previous, next, fraction);
            }
        }
        return trajectoryPoints.get(trajectoryPoints.size() - 1);
    }

    /** Projects the profile into a single sample without carrying route data over Binder. */
    public VirtualLocationProfileSnapshot sampleAt(long nowMs, long nowElapsedRealtimeNanos) {
        VirtualLocationPointSnapshot point = pointAt(nowElapsedRealtimeNanos);
        long offset = point.offsetMs();
        long sampleTime = TIME_POLICY_PROFILE.equals(timestampPolicy) && timeMs > 0L
                ? timeMs + offset : nowMs;
        long sampleElapsed = ELAPSED_POLICY_PROFILE.equals(elapsedRealtimePolicy)
                && nowElapsedRealtimeNanos > 0L ? nowElapsedRealtimeNanos + offset * 1_000_000L
                : nowElapsedRealtimeNanos;
        return new VirtualLocationProfileSnapshot(mode, provider, providerEnabled,
                point.latitude(), point.longitude(), point.altitudeMeters(), point.accuracyMeters(),
                point.speedMetersPerSecond(), point.bearingDegrees(), sampleTime, sampleElapsed,
                minimumUpdateIntervalMs, gnssEnabled, satellitesInView, satellitesUsedInFix,
                nmeaSentence, TRAJECTORY_FIXED, minimumUpdateIntervalMs, List.of(),
                TIME_POLICY_PROFILE, ELAPSED_POLICY_PROFILE);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeString(provider); out.writeInt(providerEnabled ? 1 : 0);
        out.writeLong(Double.doubleToLongBits(latitude)); out.writeLong(Double.doubleToLongBits(longitude));
        out.writeLong(Double.doubleToLongBits(altitudeMeters));
        out.writeInt(Float.floatToIntBits(accuracyMeters)); out.writeInt(Float.floatToIntBits(speedMetersPerSecond));
        out.writeInt(Float.floatToIntBits(bearingDegrees)); out.writeLong(timeMs);
        out.writeLong(elapsedRealtimeNanos); out.writeLong(minimumUpdateIntervalMs);
        out.writeInt(gnssEnabled ? 1 : 0); out.writeInt(satellitesInView);
        out.writeInt(satellitesUsedInFix); out.writeString(nmeaSentence);
        out.writeString(trajectoryMode); out.writeLong(trajectoryIntervalMs);
        out.writeTypedList(trajectoryPoints); out.writeString(timestampPolicy);
        out.writeString(elapsedRealtimePolicy);
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
    private static String trajectory(String value) {
        String normalized = ContractChecks.requiredText(value, "trajectoryMode", 16)
                .toUpperCase(Locale.ROOT);
        if (!TRAJECTORY_FIXED.equals(normalized) && !TRAJECTORY_WALKING.equals(normalized)
                && !TRAJECTORY_CYCLING.equals(normalized) && !TRAJECTORY_DRIVING.equals(normalized)
                && !TRAJECTORY_ROUTE.equals(normalized)) {
            throw new IllegalArgumentException("unsupported trajectory mode: " + value);
        }
        return normalized;
    }
    private static String policy(String value, String defaultValue, String field) {
        String normalized = ContractChecks.optionalText(value, field, 16).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) normalized = defaultValue;
        if (!TIME_POLICY_NOW.equals(normalized) && !TIME_POLICY_PROFILE.equals(normalized)) {
            throw new IllegalArgumentException("unsupported " + field + ": " + value);
        }
        return normalized;
    }
    private static VirtualLocationPointSnapshot interpolate(VirtualLocationPointSnapshot a,
            VirtualLocationPointSnapshot b, double fraction) {
        return new VirtualLocationPointSnapshot(
                a.offsetMs() + Math.round((b.offsetMs() - a.offsetMs()) * fraction),
                lerp(a.latitude(), b.latitude(), fraction),
                lerp(a.longitude(), b.longitude(), fraction),
                lerp(a.altitudeMeters(), b.altitudeMeters(), fraction),
                (float) lerp(a.accuracyMeters(), b.accuracyMeters(), fraction),
                (float) lerp(a.speedMetersPerSecond(), b.speedMetersPerSecond(), fraction),
                (float) lerp(a.bearingDegrees(), b.bearingDegrees(), fraction));
    }
    private static double lerp(double a, double b, double fraction) {
        return a + (b - a) * fraction;
    }
    private static int boundedSatellites(int value, String field) {
        if (value < 0 || value > 64) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
}
