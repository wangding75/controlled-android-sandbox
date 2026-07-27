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
        this.packageName = required(packageName, "packageName");
        if (virtualUserId < 0 || virtualUserId > 999) {
            throw new IllegalArgumentException("virtualUserId out of range");
        }
        this.virtualUserId = virtualUserId;
        this.label = value(label);
        this.versionName = value(versionName);
        this.versionCode = versionCode;
        this.signatureSha256 = required(signatureSha256, "signatureSha256");
        this.apkSha256 = required(apkSha256, "apkSha256");
        this.launchActivity = value(launchActivity);
        this.applicationClass = value(applicationClass);
        this.enabled = enabled;
        this.components = new ArrayList<>(components == null ? List.of() : components);
        this.permissions = new ArrayList<>(permissions == null ? List.of() : permissions);
        this.appOps = new ArrayList<>(appOps == null ? List.of() : appOps);
    }

    private VirtualPackageStateSnapshot(Parcel in) {
        this(in.readString(), in.readInt(), in.readString(), in.readString(), in.readLong(),
                in.readString(), in.readString(), in.readString(), in.readString(),
                in.readInt() != 0,
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
    public List<VirtualComponentSnapshot> components() { return Collections.unmodifiableList(components); }
    public List<VirtualPermissionSnapshot> permissions() { return Collections.unmodifiableList(permissions); }
    public List<PackageAppOpSnapshot> appOps() { return Collections.unmodifiableList(appOps); }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(packageName); out.writeInt(virtualUserId); out.writeString(label);
        out.writeString(versionName); out.writeLong(versionCode); out.writeString(signatureSha256);
        out.writeString(apkSha256); out.writeString(launchActivity); out.writeString(applicationClass);
        out.writeInt(enabled ? 1 : 0); out.writeTypedList(components); out.writeTypedList(permissions);
        out.writeTypedList(appOps);
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

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
    private static String value(String value) { return value == null ? "" : value; }
}
