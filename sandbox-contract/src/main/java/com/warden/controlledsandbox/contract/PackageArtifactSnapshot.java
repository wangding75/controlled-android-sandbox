package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Locale;

/** Typed Binder description of one APK artifact inside an immutable revision. */
public final class PackageArtifactSnapshot implements Parcelable {
    private final String splitName;
    private final String type;
    private final String configForSplit;
    private final String usesSplit;
    private final String path;
    private final String sha256;

    public PackageArtifactSnapshot(String splitName, String type, String configForSplit,
                                   String usesSplit, String path, String sha256) {
        this.splitName = splitValue(splitName, "splitName");
        this.type = normalizeType(type);
        this.configForSplit = splitValue(configForSplit, "configForSplit");
        this.usesSplit = splitValue(usesSplit, "usesSplit");
        this.path = required(path, "path");
        this.sha256 = digest(sha256, "sha256");
        if (base()) {
            if (!this.splitName.isEmpty() || !this.configForSplit.isEmpty()
                    || !this.usesSplit.isEmpty()) {
                throw new IllegalArgumentException("Base artifact cannot declare split metadata");
            }
        } else {
            if (this.splitName.isEmpty()) throw new IllegalArgumentException("Split artifact requires splitName");
            if ("CONFIG".equals(this.type) && this.configForSplit.isEmpty()) {
                throw new IllegalArgumentException("Configuration split requires configForSplit");
            }
            if (!"CONFIG".equals(this.type) && !this.configForSplit.isEmpty()) {
                throw new IllegalArgumentException("Only configuration splits may declare configForSplit");
            }
        }
    }

    private PackageArtifactSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString());
    }

    public String splitName() { return splitName; }
    public String type() { return type; }
    public String configForSplit() { return configForSplit; }
    public String usesSplit() { return usesSplit; }
    public String path() { return path; }
    public String sha256() { return sha256; }
    public boolean base() { return "BASE".equals(type); }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(splitName); out.writeString(type); out.writeString(configForSplit);
        out.writeString(usesSplit); out.writeString(path); out.writeString(sha256);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<PackageArtifactSnapshot> CREATOR = new Creator<>() {
        @Override public PackageArtifactSnapshot createFromParcel(Parcel in) { return new PackageArtifactSnapshot(in); }
        @Override public PackageArtifactSnapshot[] newArray(int size) { return new PackageArtifactSnapshot[size]; }
    };

    private static String normalizeType(String value) {
        String normalized = required(value, "type").toUpperCase(Locale.ROOT);
        if (!"BASE".equals(normalized) && !"FEATURE".equals(normalized)
                && !"CONFIG".equals(normalized)) {
            throw new IllegalArgumentException("Unsupported artifact type: " + value);
        }
        return normalized;
    }
    private static String digest(String value, String name) {
        String normalized = required(value, name).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must contain 64 hexadecimal characters");
        }
        return normalized;
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
    private static String splitValue(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isEmpty() && !"base".equals(normalized)
                && !normalized.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }
}
