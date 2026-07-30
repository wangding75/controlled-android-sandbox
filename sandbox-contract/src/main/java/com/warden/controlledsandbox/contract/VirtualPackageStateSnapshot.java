package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable package/component/permission/AppOps state for one virtual user. */
public final class VirtualPackageStateSnapshot implements Parcelable {
    private final String packageName;
    private final int virtualUserId;
    private final String label;
    private final String versionName;
    private final long versionCode;
    private final String signatureSha256;
    private final String apkSha256;
    private final String launchActivity;
    private final String applicationClass;
    private final boolean enabled;
    private final long firstInstallTime;
    private final long lastUpdateTime;
    private final String installerPackageName;
    private final ArrayList<String> splitNames;
    private final ArrayList<String> sharedLibraries;
    private final ArrayList<VirtualSharedLibrarySnapshot> sharedLibraryDetails;
    private final ArrayList<VirtualInstrumentationSnapshot> instrumentations;
    private final ArrayList<VirtualComponentSnapshot> components;
    private final ArrayList<VirtualPermissionSnapshot> permissions;
    private final ArrayList<PackageAppOpSnapshot> appOps;

    public VirtualPackageStateSnapshot(String packageName, int virtualUserId, String label,
                                       String versionName, long versionCode,
                                       String signatureSha256, String apkSha256,
                                       String launchActivity, String applicationClass,
                                       boolean enabled,
                                       List<VirtualComponentSnapshot> components,
                                       List<VirtualPermissionSnapshot> permissions,
                                       List<PackageAppOpSnapshot> appOps) {
        this(packageName, virtualUserId, label, versionName, versionCode, signatureSha256,
                apkSha256, launchActivity, applicationClass, enabled, 0L, 0L, "",
                List.of(), List.of(), components, permissions, appOps);
    }

    public VirtualPackageStateSnapshot(String packageName, int virtualUserId, String label,
                                       String versionName, long versionCode,
                                       String signatureSha256, String apkSha256,
                                       String launchActivity, String applicationClass,
                                       boolean enabled, List<String> splitNames,
                                       List<String> sharedLibraries,
                                       List<VirtualComponentSnapshot> components,
                                       List<VirtualPermissionSnapshot> permissions,
                                       List<PackageAppOpSnapshot> appOps) {
        this(packageName, virtualUserId, label, versionName, versionCode, signatureSha256,
                apkSha256, launchActivity, applicationClass, enabled, 0L, 0L, "",
                splitNames, sharedLibraries, components, permissions, appOps);
    }

    public VirtualPackageStateSnapshot(String packageName, int virtualUserId, String label,
                                       String versionName, long versionCode,
                                       String signatureSha256, String apkSha256,
                                       String launchActivity, String applicationClass,
                                       boolean enabled, long firstInstallTime,
                                       long lastUpdateTime, String installerPackageName,
                                       List<String> splitNames, List<String> sharedLibraries,
                                       List<VirtualComponentSnapshot> components,
                                       List<VirtualPermissionSnapshot> permissions,
                                       List<PackageAppOpSnapshot> appOps) {
        this(packageName, virtualUserId, label, versionName, versionCode, signatureSha256,
                apkSha256, launchActivity, applicationClass, enabled, firstInstallTime,
                lastUpdateTime, installerPackageName, splitNames, sharedLibraries, List.of(),
                List.of(), components, permissions, appOps);
    }

    public VirtualPackageStateSnapshot(String packageName, int virtualUserId, String label,
                                       String versionName, long versionCode,
                                       String signatureSha256, String apkSha256,
                                       String launchActivity, String applicationClass,
                                       boolean enabled, long firstInstallTime,
                                       long lastUpdateTime, String installerPackageName,
                                       List<String> splitNames, List<String> sharedLibraries,
                                       List<VirtualSharedLibrarySnapshot> sharedLibraryDetails,
                                       List<VirtualInstrumentationSnapshot> instrumentations,
                                       List<VirtualComponentSnapshot> components,
                                       List<VirtualPermissionSnapshot> permissions,
                                       List<PackageAppOpSnapshot> appOps) {
        this.packageName = required(packageName, "packageName");
        if (virtualUserId < 0 || virtualUserId > 999) {
            throw new IllegalArgumentException("virtualUserId out of range");
        }
        this.virtualUserId = virtualUserId;
        this.label = value(label);
        this.versionName = value(versionName);
        this.versionCode = versionCode;
        this.signatureSha256 = required(signatureSha256, "signatureSha256");
        this.apkSha256 = digest(apkSha256, "apkSha256");
        this.launchActivity = value(launchActivity);
        this.applicationClass = value(applicationClass);
        this.enabled = enabled;
        if (firstInstallTime < 0 || lastUpdateTime < 0
                || (firstInstallTime > 0 && lastUpdateTime > 0 && lastUpdateTime < firstInstallTime)) {
            throw new IllegalArgumentException("Invalid package install timestamps");
        }
        this.firstInstallTime = firstInstallTime;
        this.lastUpdateTime = lastUpdateTime;
        this.installerPackageName = value(installerPackageName);
        this.splitNames = validatedNames(splitNames, "splitName", 255);
        this.sharedLibraries = validatedNames(sharedLibraries, "sharedLibrary", 1024);
        this.sharedLibraryDetails = new ArrayList<>(sharedLibraryDetails == null ? List.of() : sharedLibraryDetails);
        this.instrumentations = new ArrayList<>(instrumentations == null ? List.of() : instrumentations);
        if (this.sharedLibraryDetails.size() > 1024) {
            throw new IllegalArgumentException("sharedLibraryDetails list is too large");
        }
        if (this.instrumentations.size() > 256) {
            throw new IllegalArgumentException("instrumentations list is too large");
        }
        this.components = new ArrayList<>(components == null ? List.of() : components);
        this.permissions = new ArrayList<>(permissions == null ? List.of() : permissions);
        this.appOps = new ArrayList<>(appOps == null ? List.of() : appOps);
    }

    private VirtualPackageStateSnapshot(Parcel in) {
        this(in.readString(), in.readInt(), in.readString(), in.readString(), in.readLong(),
                in.readString(), in.readString(), in.readString(), in.readString(),
                in.readInt() != 0, in.readLong(), in.readLong(), in.readString(),
                in.createStringArrayList(), in.createStringArrayList(),
                in.createTypedArrayList(VirtualSharedLibrarySnapshot.CREATOR),
                in.createTypedArrayList(VirtualInstrumentationSnapshot.CREATOR),
                in.createTypedArrayList(VirtualComponentSnapshot.CREATOR),
                in.createTypedArrayList(VirtualPermissionSnapshot.CREATOR),
                in.createTypedArrayList(PackageAppOpSnapshot.CREATOR));
    }

    public String packageName() { return packageName; }
    public int virtualUserId() { return virtualUserId; }
    public String label() { return label; }
    public String versionName() { return versionName; }
    public long versionCode() { return versionCode; }
    public String signatureSha256() { return signatureSha256; }
    public String apkSha256() { return apkSha256; }
    public String launchActivity() { return launchActivity; }
    public String applicationClass() { return applicationClass; }
    public boolean enabled() { return enabled; }
    public long firstInstallTime() { return firstInstallTime; }
    public long lastUpdateTime() { return lastUpdateTime; }
    public String installerPackageName() { return installerPackageName; }
    public List<String> splitNames() { return Collections.unmodifiableList(splitNames); }
    public List<String> sharedLibraries() { return Collections.unmodifiableList(sharedLibraries); }
    public List<VirtualSharedLibrarySnapshot> sharedLibraryDetails() {
        return Collections.unmodifiableList(sharedLibraryDetails);
    }
    public List<VirtualInstrumentationSnapshot> instrumentations() {
        return Collections.unmodifiableList(instrumentations);
    }
    public List<VirtualComponentSnapshot> components() { return Collections.unmodifiableList(components); }
    public List<VirtualPermissionSnapshot> permissions() { return Collections.unmodifiableList(permissions); }
    public List<PackageAppOpSnapshot> appOps() { return Collections.unmodifiableList(appOps); }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(packageName); out.writeInt(virtualUserId); out.writeString(label);
        out.writeString(versionName); out.writeLong(versionCode); out.writeString(signatureSha256);
        out.writeString(apkSha256); out.writeString(launchActivity); out.writeString(applicationClass);
        out.writeInt(enabled ? 1 : 0); out.writeLong(firstInstallTime); out.writeLong(lastUpdateTime);
        out.writeString(installerPackageName); out.writeStringList(splitNames);
        out.writeStringList(sharedLibraries); out.writeTypedList(sharedLibraryDetails);
        out.writeTypedList(instrumentations); out.writeTypedList(components);
        out.writeTypedList(permissions); out.writeTypedList(appOps);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualPackageStateSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualPackageStateSnapshot createFromParcel(Parcel in) {
            return new VirtualPackageStateSnapshot(in);
        }
        @Override public VirtualPackageStateSnapshot[] newArray(int size) {
            return new VirtualPackageStateSnapshot[size];
        }
    };

    private static ArrayList<String> validatedNames(List<String> input, String name, int maximum) {
        ArrayList<String> output = new ArrayList<>();
        java.util.Set<String> unique = new java.util.LinkedHashSet<>();
        if (input == null) return output;
        if (input.size() > maximum) throw new IllegalArgumentException(name + " list is too large");
        for (String value : input) {
            String normalized = required(value, name);
            if (!unique.add(normalized)) throw new IllegalArgumentException("Duplicate " + name + ": " + normalized);
            output.add(normalized);
        }
        return output;
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
