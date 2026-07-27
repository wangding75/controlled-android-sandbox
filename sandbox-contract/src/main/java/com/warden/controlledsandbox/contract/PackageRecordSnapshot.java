package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable Binder representation of trusted installed-package metadata. */
public final class PackageRecordSnapshot implements Parcelable {
    private final String packageName;
    private final String label;
    private final String versionName;
    private final long versionCode;
    private final String signatureSha256;
    private final String apkPath;
    private final String nativeLibraryDir;
    private final String launchActivity;
    private final String launchProcess;
    private final String applicationClass;
    private final String serviceClass;
    private final String serviceProcess;
    private final String receiverClass;
    private final String receiverProcess;
    private final String receiverAction;
    private final String providerClass;
    private final String providerProcess;
    private final String providerAuthority;
    private final String permissions;
    private final String sharedLibraries;
    private final String apkSha256;
    private final String baseApkSha256;
    private final ArrayList<PackageArtifactSnapshot> artifacts;
    private final long importedAt;
    private final String lastProbeStatus;
    private final long lastProbeAt;

    public PackageRecordSnapshot(String packageName, String label, String versionName,
                                 long versionCode, String signatureSha256, String apkPath,
                                 String nativeLibraryDir, String launchActivity,
                                 String launchProcess, String applicationClass,
                                 String serviceClass, String serviceProcess,
                                 String receiverClass, String receiverProcess,
                                 String receiverAction, String providerClass,
                                 String providerProcess, String providerAuthority,
                                 String permissions, String apkSha256, long importedAt,
                                 String lastProbeStatus, long lastProbeAt) {
        this(packageName, label, versionName, versionCode, signatureSha256, apkPath,
                nativeLibraryDir, launchActivity, launchProcess, applicationClass,
                serviceClass, serviceProcess, receiverClass, receiverProcess, receiverAction,
                providerClass, providerProcess, providerAuthority, permissions, "", apkSha256,
                apkSha256, List.of(new PackageArtifactSnapshot("", "BASE", "", "", apkPath, apkSha256)),
                importedAt, lastProbeStatus, lastProbeAt);
    }

    public PackageRecordSnapshot(String packageName, String label, String versionName,
                                 long versionCode, String signatureSha256, String apkPath,
                                 String nativeLibraryDir, String launchActivity,
                                 String launchProcess, String applicationClass,
                                 String serviceClass, String serviceProcess,
                                 String receiverClass, String receiverProcess,
                                 String receiverAction, String providerClass,
                                 String providerProcess, String providerAuthority,
                                 String permissions, String sharedLibraries,
                                 String apkSha256, String baseApkSha256,
                                 List<PackageArtifactSnapshot> artifacts, long importedAt,
                                 String lastProbeStatus, long lastProbeAt) {
        this.packageName = required(packageName, "packageName");
        this.label = value(label); this.versionName = value(versionName); this.versionCode = versionCode;
        this.signatureSha256 = required(signatureSha256, "signatureSha256");
        this.apkPath = required(apkPath, "apkPath"); this.nativeLibraryDir = value(nativeLibraryDir);
        this.launchActivity = value(launchActivity); this.launchProcess = value(launchProcess);
        this.applicationClass = value(applicationClass); this.serviceClass = value(serviceClass);
        this.serviceProcess = value(serviceProcess); this.receiverClass = value(receiverClass);
        this.receiverProcess = value(receiverProcess); this.receiverAction = value(receiverAction);
        this.providerClass = value(providerClass); this.providerProcess = value(providerProcess);
        this.providerAuthority = value(providerAuthority); this.permissions = value(permissions);
        this.sharedLibraries = value(sharedLibraries);
        this.apkSha256 = digest(apkSha256, "apkSha256");
        this.baseApkSha256 = digest(baseApkSha256, "baseApkSha256");
        this.artifacts = new ArrayList<>(artifacts == null ? List.of() : artifacts);
        validateArtifacts(this.artifacts, this.apkPath, this.baseApkSha256);
        this.importedAt = importedAt; this.lastProbeStatus = value(lastProbeStatus); this.lastProbeAt = lastProbeAt;
    }

    private PackageRecordSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readLong(), in.readString(),
                in.readString(), in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.createTypedArrayList(PackageArtifactSnapshot.CREATOR),
                in.readLong(), in.readString(), in.readLong());
    }

    public String packageName() { return packageName; } public String label() { return label; }
    public String versionName() { return versionName; } public long versionCode() { return versionCode; }
    public String signatureSha256() { return signatureSha256; } public String apkPath() { return apkPath; }
    public String nativeLibraryDir() { return nativeLibraryDir; } public String launchActivity() { return launchActivity; }
    public String launchProcess() { return launchProcess; } public String applicationClass() { return applicationClass; }
    public String serviceClass() { return serviceClass; } public String serviceProcess() { return serviceProcess; }
    public String receiverClass() { return receiverClass; } public String receiverProcess() { return receiverProcess; }
    public String receiverAction() { return receiverAction; } public String providerClass() { return providerClass; }
    public String providerProcess() { return providerProcess; } public String providerAuthority() { return providerAuthority; }
    public String permissions() { return permissions; } public String sharedLibraries() { return sharedLibraries; }
    public String apkSha256() { return apkSha256; } public String baseApkSha256() { return baseApkSha256; }
    public List<PackageArtifactSnapshot> artifacts() { return Collections.unmodifiableList(artifacts); }
    public long importedAt() { return importedAt; } public String lastProbeStatus() { return lastProbeStatus; }
    public long lastProbeAt() { return lastProbeAt; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(packageName); out.writeString(label); out.writeString(versionName); out.writeLong(versionCode);
        out.writeString(signatureSha256); out.writeString(apkPath); out.writeString(nativeLibraryDir);
        out.writeString(launchActivity); out.writeString(launchProcess); out.writeString(applicationClass);
        out.writeString(serviceClass); out.writeString(serviceProcess); out.writeString(receiverClass);
        out.writeString(receiverProcess); out.writeString(receiverAction); out.writeString(providerClass);
        out.writeString(providerProcess); out.writeString(providerAuthority); out.writeString(permissions);
        out.writeString(sharedLibraries); out.writeString(apkSha256); out.writeString(baseApkSha256);
        out.writeTypedList(artifacts); out.writeLong(importedAt); out.writeString(lastProbeStatus); out.writeLong(lastProbeAt);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<PackageRecordSnapshot> CREATOR = new Creator<>() {
        @Override public PackageRecordSnapshot createFromParcel(Parcel in) { return new PackageRecordSnapshot(in); }
        @Override public PackageRecordSnapshot[] newArray(int size) { return new PackageRecordSnapshot[size]; }
    };
    private static void validateArtifacts(List<PackageArtifactSnapshot> artifacts,
                                          String apkPath, String baseApkSha256) {
        if (artifacts.isEmpty() || artifacts.size() > 256) {
            throw new IllegalArgumentException("artifacts size is invalid");
        }
        int baseCount = 0;
        java.util.Set<String> splitNames = new java.util.HashSet<>();
        for (PackageArtifactSnapshot artifact : artifacts) {
            if (artifact == null) throw new IllegalArgumentException("artifact is required");
            if (artifact.base()) {
                baseCount++;
                if (!apkPath.equals(artifact.path()) || !baseApkSha256.equals(artifact.sha256())) {
                    throw new IllegalArgumentException("Base artifact does not match package record");
                }
            } else if (!splitNames.add(artifact.splitName())) {
                throw new IllegalArgumentException("Duplicate split artifact: " + artifact.splitName());
            }
        }
        if (baseCount != 1) throw new IllegalArgumentException("Exactly one base artifact is required");
    }
    private static String digest(String value, String name) {
        String normalized = required(value, name).toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must contain 64 hexadecimal characters");
        }
        return normalized;
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
    private static String value(String value) { return value == null ? "" : value; }
}
