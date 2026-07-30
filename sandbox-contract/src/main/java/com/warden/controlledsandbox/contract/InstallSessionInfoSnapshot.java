package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Locale;

/** Immutable PackageInstaller-style session status owned by the package service. */
public final class InstallSessionInfoSnapshot implements Parcelable {
    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_SEALED = "SEALED";
    public static final String STATE_COMMITTING = "COMMITTING";
    public static final String STATE_FAILED = "FAILED";

    private final int sessionId;
    private final String state;
    private final InstallSessionParamsSnapshot params;
    private final int artifactCount;
    private final long bytesStaged;
    private final float progress;
    private final long createdAt;
    private final long updatedAt;
    private final int attemptCount;
    private final String failureCode;
    private final String failureMessage;

    public InstallSessionInfoSnapshot(int sessionId, String state,
                                      InstallSessionParamsSnapshot params,
                                      int artifactCount, long bytesStaged, float progress,
                                      long createdAt, long updatedAt, int attemptCount,
                                      String failureCode, String failureMessage) {
        if (sessionId <= 0) throw new IllegalArgumentException("sessionId is invalid");
        this.sessionId = sessionId;
        this.state = state(state);
        if (params == null) throw new IllegalArgumentException("params is required");
        this.params = params;
        if (artifactCount < 0 || artifactCount > 256) {
            throw new IllegalArgumentException("artifactCount is invalid");
        }
        this.artifactCount = artifactCount;
        if (bytesStaged < 0 || bytesStaged > 3L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("bytesStaged is invalid");
        }
        this.bytesStaged = bytesStaged;
        if (Float.isNaN(progress) || progress < 0F || progress > 1F) {
            throw new IllegalArgumentException("progress is invalid");
        }
        this.progress = progress;
        if (createdAt <= 0 || updatedAt < createdAt) {
            throw new IllegalArgumentException("session timestamps are invalid");
        }
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        if (attemptCount < 0 || attemptCount > 1000) {
            throw new IllegalArgumentException("attemptCount is invalid");
        }
        this.attemptCount = attemptCount;
        this.failureCode = bounded(failureCode, "failureCode", 128);
        this.failureMessage = bounded(failureMessage, "failureMessage", 2048);
        if (!STATE_FAILED.equals(this.state)
                && (!this.failureCode.isEmpty() || !this.failureMessage.isEmpty())) {
            throw new IllegalArgumentException("non-failed session cannot carry failure details");
        }
    }

    private InstallSessionInfoSnapshot(Parcel in) {
        this(in.readInt(), in.readString(),
                in.readParcelable(InstallSessionParamsSnapshot.class.getClassLoader()),
                in.readInt(), in.readLong(), Float.intBitsToFloat(in.readInt()),
                in.readLong(), in.readLong(), in.readInt(), in.readString(), in.readString());
    }

    public int sessionId() { return sessionId; }
    public String state() { return state; }
    public InstallSessionParamsSnapshot params() { return params; }
    public int artifactCount() { return artifactCount; }
    public long bytesStaged() { return bytesStaged; }
    public float progress() { return progress; }
    public long createdAt() { return createdAt; }
    public long updatedAt() { return updatedAt; }
    public int attemptCount() { return attemptCount; }
    public String failureCode() { return failureCode; }
    public String failureMessage() { return failureMessage; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(sessionId); out.writeString(state); out.writeParcelable(params, flags);
        out.writeInt(artifactCount); out.writeLong(bytesStaged);
        out.writeInt(Float.floatToIntBits(progress)); out.writeLong(createdAt);
        out.writeLong(updatedAt); out.writeInt(attemptCount);
        out.writeString(failureCode); out.writeString(failureMessage);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<InstallSessionInfoSnapshot> CREATOR = new Creator<>() {
        @Override public InstallSessionInfoSnapshot createFromParcel(Parcel in) {
            return new InstallSessionInfoSnapshot(in);
        }
        @Override public InstallSessionInfoSnapshot[] newArray(int size) {
            return new InstallSessionInfoSnapshot[size];
        }
    };

    private static String state(String value) {
        String normalized = value(value).toUpperCase(Locale.ROOT);
        if (!STATE_OPEN.equals(normalized) && !STATE_SEALED.equals(normalized)
                && !STATE_COMMITTING.equals(normalized) && !STATE_FAILED.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported install session state: " + value);
        }
        return normalized;
    }
    private static String bounded(String value, String name, int maximum) {
        String normalized = value(value);
        if (normalized.length() > maximum) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }
    private static String value(String value) { return value == null ? "" : value.trim(); }
}
