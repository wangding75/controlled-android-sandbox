package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Locale;

/** Typed, bounded PackageInstaller-style session parameters. */
public final class InstallSessionParamsSnapshot implements Parcelable {
    public static final String MODE_FULL = "FULL";
    public static final String MODE_INHERIT_EXISTING = "INHERIT_EXISTING";
    public static final String USER_ACTION_UNSPECIFIED = "UNSPECIFIED";
    public static final String USER_ACTION_REQUIRED = "REQUIRED";
    public static final String USER_ACTION_NOT_REQUIRED = "NOT_REQUIRED";

    private final String mode;
    private final String expectedPackageName;
    private final String installerPackageName;
    private final String appLabel;
    private final long sizeBytes;
    private final int installFlags;
    private final boolean rollbackEnabled;
    private final String requireUserAction;

    public InstallSessionParamsSnapshot(String mode, String expectedPackageName,
                                        String installerPackageName, String appLabel,
                                        long sizeBytes, int installFlags,
                                        boolean rollbackEnabled, String requireUserAction) {
        this.mode = mode(mode);
        this.expectedPackageName = value(expectedPackageName);
        this.installerPackageName = value(installerPackageName);
        this.appLabel = bounded(appLabel, "appLabel", 256);
        if (sizeBytes < -1L || sizeBytes > 3L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("sizeBytes is invalid");
        }
        this.sizeBytes = sizeBytes;
        if (installFlags < 0) throw new IllegalArgumentException("installFlags is invalid");
        this.installFlags = installFlags;
        this.rollbackEnabled = rollbackEnabled;
        this.requireUserAction = userAction(requireUserAction);
        if (MODE_INHERIT_EXISTING.equals(this.mode) && this.expectedPackageName.isEmpty()) {
            throw new IllegalArgumentException("inherit-existing mode requires expectedPackageName");
        }
    }

    private InstallSessionParamsSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readString(), in.readLong(),
                in.readInt(), in.readInt() != 0, in.readString());
    }

    public static InstallSessionParamsSnapshot fullInstall(String expectedPackageName) {
        return new InstallSessionParamsSnapshot(MODE_FULL, expectedPackageName,
                "com.warden.virtualinstaller", "", -1L, 0, false,
                USER_ACTION_UNSPECIFIED);
    }

    public String mode() { return mode; }
    public String expectedPackageName() { return expectedPackageName; }
    public String installerPackageName() { return installerPackageName; }
    public String appLabel() { return appLabel; }
    public long sizeBytes() { return sizeBytes; }
    public int installFlags() { return installFlags; }
    public boolean rollbackEnabled() { return rollbackEnabled; }
    public String requireUserAction() { return requireUserAction; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeString(expectedPackageName);
        out.writeString(installerPackageName); out.writeString(appLabel);
        out.writeLong(sizeBytes); out.writeInt(installFlags);
        out.writeInt(rollbackEnabled ? 1 : 0); out.writeString(requireUserAction);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<InstallSessionParamsSnapshot> CREATOR = new Creator<>() {
        @Override public InstallSessionParamsSnapshot createFromParcel(Parcel in) {
            return new InstallSessionParamsSnapshot(in);
        }
        @Override public InstallSessionParamsSnapshot[] newArray(int size) {
            return new InstallSessionParamsSnapshot[size];
        }
    };

    private static String mode(String value) {
        String normalized = value(value).toUpperCase(Locale.ROOT);
        if (!MODE_FULL.equals(normalized) && !MODE_INHERIT_EXISTING.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported install mode: " + value);
        }
        return normalized;
    }
    private static String userAction(String value) {
        String normalized = value(value).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) normalized = USER_ACTION_UNSPECIFIED;
        if (!USER_ACTION_UNSPECIFIED.equals(normalized)
                && !USER_ACTION_REQUIRED.equals(normalized)
                && !USER_ACTION_NOT_REQUIRED.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported requireUserAction: " + value);
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
