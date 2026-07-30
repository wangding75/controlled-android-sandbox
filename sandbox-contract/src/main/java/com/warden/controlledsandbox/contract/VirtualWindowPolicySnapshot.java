package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Window/session policy for one package and virtual user. */
public final class VirtualWindowPolicySnapshot implements Parcelable {
    public static final String MODE_BLOCKED = "BLOCKED";
    public static final String MODE_STATIC = "STATIC";
    public static final String MODE_HOST = "HOST";

    private final String mode;
    private final int maximumWindows;
    private final boolean rewritePackageName;
    private final boolean allowSecureFlag;
    private final boolean allowSystemAlertWindows;
    private final boolean allowScreenCaptureControl;

    public VirtualWindowPolicySnapshot(String mode, int maximumWindows,
            boolean rewritePackageName, boolean allowSecureFlag,
            boolean allowSystemAlertWindows, boolean allowScreenCaptureControl) {
        this.mode = mode(mode);
        if (maximumWindows < 1 || maximumWindows > 256) {
            throw new IllegalArgumentException("maximumWindows must be in [1,256]");
        }
        this.maximumWindows = maximumWindows;
        this.rewritePackageName = rewritePackageName;
        this.allowSecureFlag = allowSecureFlag;
        this.allowSystemAlertWindows = allowSystemAlertWindows;
        this.allowScreenCaptureControl = allowScreenCaptureControl;
    }

    private VirtualWindowPolicySnapshot(Parcel in) {
        this(in.readString(), in.readInt(), in.readInt() != 0, in.readInt() != 0,
                in.readInt() != 0, in.readInt() != 0);
    }

    public String mode() { return mode; }
    public int maximumWindows() { return maximumWindows; }
    public boolean rewritePackageName() { return rewritePackageName; }
    public boolean allowSecureFlag() { return allowSecureFlag; }
    public boolean allowSystemAlertWindows() { return allowSystemAlertWindows; }
    public boolean allowScreenCaptureControl() { return allowScreenCaptureControl; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeInt(maximumWindows);
        out.writeInt(rewritePackageName ? 1 : 0); out.writeInt(allowSecureFlag ? 1 : 0);
        out.writeInt(allowSystemAlertWindows ? 1 : 0);
        out.writeInt(allowScreenCaptureControl ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualWindowPolicySnapshot> CREATOR = new Creator<>() {
        @Override public VirtualWindowPolicySnapshot createFromParcel(Parcel in) {
            return new VirtualWindowPolicySnapshot(in);
        }
        @Override public VirtualWindowPolicySnapshot[] newArray(int size) {
            return new VirtualWindowPolicySnapshot[size];
        }
    };

    private static String mode(String value) {
        String normalized = ContractChecks.requiredText(value, "mode", 16).toUpperCase(java.util.Locale.ROOT);
        if (!MODE_BLOCKED.equals(normalized) && !MODE_STATIC.equals(normalized)
                && !MODE_HOST.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported window mode: " + value);
        }
        return normalized;
    }
}
