package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Locale;

/** Typed deterministic SystemUpdateManager state. */
public final class VirtualSystemUpdateProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean queryEnabled;
    private final boolean allowStatusSubmission;
    private final String status;
    private final String title;
    private final String version;
    private final String securityPatch;
    private final int progressPercent;
    private final long receivedTimeMs;

    public VirtualSystemUpdateProfileSnapshot(String mode, boolean queryEnabled,
            boolean allowStatusSubmission, String status, String title, String version,
            String securityPatch, int progressPercent, long receivedTimeMs) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.queryEnabled = queryEnabled;
        this.allowStatusSubmission = allowStatusSubmission;
        this.status = status(status);
        this.title = ContractChecks.optionalText(title, "title", 256);
        this.version = ContractChecks.optionalText(version, "version", 128);
        this.securityPatch = ContractChecks.optionalText(securityPatch, "securityPatch", 32);
        if (progressPercent < 0 || progressPercent > 100) {
            throw new IllegalArgumentException("progressPercent must be in [0,100]");
        }
        this.progressPercent = progressPercent;
        this.receivedTimeMs = ContractChecks.nonNegative(receivedTimeMs, "receivedTimeMs");
    }

    private VirtualSystemUpdateProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt() != 0, in.readString(),
                in.readString(), in.readString(), in.readString(), in.readInt(), in.readLong());
    }

    public String mode() { return mode; }
    public boolean queryEnabled() { return queryEnabled; }
    public boolean allowStatusSubmission() { return allowStatusSubmission; }
    public String status() { return status; }
    public String title() { return title; }
    public String version() { return version; }
    public String securityPatch() { return securityPatch; }
    public int progressPercent() { return progressPercent; }
    public long receivedTimeMs() { return receivedTimeMs; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(queryEnabled ? 1 : 0);
        out.writeInt(allowStatusSubmission ? 1 : 0);
        out.writeString(status);
        out.writeString(title);
        out.writeString(version);
        out.writeString(securityPatch);
        out.writeInt(progressPercent);
        out.writeLong(receivedTimeMs);
    }
    @Override public int describeContents() { return 0; }

    private static String status(String value) {
        String normalized = ContractChecks.requiredText(value, "status", 32)
                .toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("UNKNOWN", "IDLE", "WAITING", "IN_PROGRESS", "PAUSED",
                "ERROR", "UPDATED").contains(normalized)) {
            throw new IllegalArgumentException("unsupported system update status: " + value);
        }
        return normalized;
    }

    public static final Creator<VirtualSystemUpdateProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualSystemUpdateProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualSystemUpdateProfileSnapshot(in);
        }
        @Override public VirtualSystemUpdateProfileSnapshot[] newArray(int size) {
            return new VirtualSystemUpdateProfileSnapshot[size];
        }
    };
}
