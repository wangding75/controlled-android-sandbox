package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

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
    private final String apkSha256;
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
        this.packageName = required(packageName, "packageName");
        this.label = value(label);
        this.versionName = value(versionName);
        this.versionCode = versionCode;
        this.signatureSha256 = required(signatureSha256, "signatureSha256");
        this.apkPath = required(apkPath, "apkPath");
        this.nativeLibraryDir = value(nativeLibraryDir);
        this.launchActivity = value(launchActivity);
        this.launchProcess = value(launchProcess);
        this.applicationClass = value(applicationClass);
        this.serviceClass = value(serviceClass);
        this.serviceProcess = value(serviceProcess);
        this.receiverClass = value(receiverClass);
        this.receiverProcess = value(receiverProcess);
        this.receiverAction = value(receiverAction);
        this.providerClass = value(providerClass);
        this.providerProcess = value(providerProcess);
        this.providerAuthority = value(providerAuthority);
        this.permissions = value(permissions);
        this.apkSha256 = required(apkSha256, "apkSha256");
        this.importedAt = importedAt;
        this.lastProbeStatus = value(lastProbeStatus);
        this.lastProbeAt = lastProbeAt;
    }

    private PackageRecordSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readLong(),
                in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.readString(), in.readString(),
                in.readLong(), in.readString(), in.readLong());
    }

    public String packageName() { return packageName; }
    public String label() { return label; }
    public String versionName() { return versionName; }
    public long versionCode() { return versionCode; }
    public String signatureSha256() { return signatureSha256; }
    public String apkPath() { return apkPath; }
    public String nativeLibraryDir() { return nativeLibraryDir; }
    public String launchActivity() { return launchActivity; }
    public String launchProcess() { return launchProcess; }
    public String applicationClass() { return applicationClass; }
    public String serviceClass() { return serviceClass; }
    public String serviceProcess() { return serviceProcess; }
    public String receiverClass() { return receiverClass; }
    public String receiverProcess() { return receiverProcess; }
    public String receiverAction() { return receiverAction; }
    public String providerClass() { return providerClass; }
    public String providerProcess() { return providerProcess; }
    public String providerAuthority() { return providerAuthority; }
    public String permissions() { return permissions; }
    public String apkSha256() { return apkSha256; }
    public long importedAt() { return importedAt; }
    public String lastProbeStatus() { return lastProbeStatus; }
    public long lastProbeAt() { return lastProbeAt; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(packageName); out.writeString(label); out.writeString(versionName);
        out.writeLong(versionCode); out.writeString(signatureSha256); out.writeString(apkPath);
        out.writeString(nativeLibraryDir); out.writeString(launchActivity);
        out.writeString(launchProcess); out.writeString(applicationClass);
        out.writeString(serviceClass); out.writeString(serviceProcess);
        out.writeString(receiverClass); out.writeString(receiverProcess);
        out.writeString(receiverAction); out.writeString(providerClass);
        out.writeString(providerProcess); out.writeString(providerAuthority);
        out.writeString(permissions); out.writeString(apkSha256); out.writeLong(importedAt);
        out.writeString(lastProbeStatus); out.writeLong(lastProbeAt);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<PackageRecordSnapshot> CREATOR = new Creator<>() {
        @Override public PackageRecordSnapshot createFromParcel(Parcel in) {
            return new PackageRecordSnapshot(in);
        }
        @Override public PackageRecordSnapshot[] newArray(int size) {
            return new PackageRecordSnapshot[size];
        }
    };

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
    private static String value(String value) { return value == null ? "" : value; }
}
