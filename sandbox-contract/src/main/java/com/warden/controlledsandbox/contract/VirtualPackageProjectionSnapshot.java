package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Read-only projection of another installed virtual package for the Guest PackageManager.
 *
 * <p>This is deliberately metadata-only. It is never used to execute the projected APK; the
 * current Guest session remains the only executable package. The projection exists so API 32
 * PackageManager queries can see the same virtual package universe as the package authority.</p>
 */
public final class VirtualPackageProjectionSnapshot implements Parcelable {
    private final VirtualPackageStateSnapshot packageState;
    private final String apkPath;
    private final String nativeLibraryDir;
    private final int virtualUid;

    public VirtualPackageProjectionSnapshot(VirtualPackageStateSnapshot packageState,
                                            String apkPath, String nativeLibraryDir,
                                            int virtualUid) {
        if (packageState == null) throw new IllegalArgumentException("packageState is required");
        if (apkPath == null || apkPath.trim().isEmpty()) {
            throw new IllegalArgumentException("apkPath is required");
        }
        if (virtualUid < 0) throw new IllegalArgumentException("virtualUid must be non-negative");
        this.packageState = packageState;
        this.apkPath = apkPath.trim();
        this.nativeLibraryDir = nativeLibraryDir == null ? "" : nativeLibraryDir.trim();
        this.virtualUid = virtualUid;
    }

    private VirtualPackageProjectionSnapshot(Parcel in) {
        this(in.readTypedObject(VirtualPackageStateSnapshot.CREATOR), in.readString(),
                in.readString(), in.readInt());
    }

    public VirtualPackageStateSnapshot packageState() { return packageState; }
    public String apkPath() { return apkPath; }
    public String nativeLibraryDir() { return nativeLibraryDir; }
    public int virtualUid() { return virtualUid; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeTypedObject(packageState, flags);
        out.writeString(apkPath);
        out.writeString(nativeLibraryDir);
        out.writeInt(virtualUid);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualPackageProjectionSnapshot> CREATOR =
            new Creator<>() {
                @Override public VirtualPackageProjectionSnapshot createFromParcel(Parcel in) {
                    return new VirtualPackageProjectionSnapshot(in);
                }

                @Override public VirtualPackageProjectionSnapshot[] newArray(int size) {
                    return new VirtualPackageProjectionSnapshot[size];
                }
            };
}
