package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Runtime identity advertised by the independently installed 32-bit Companion APK. */
public final class NativeCompanionIdentity implements Parcelable {
    private final String product;
    private final String releaseTrain;
    private final int versionCode;
    private final String versionName;
    private final int minimumProtocol;
    private final int maximumProtocol;

    public NativeCompanionIdentity(
            String product,
            String releaseTrain,
            int versionCode,
            String versionName,
            int minimumProtocol,
            int maximumProtocol) {
        this.product = required(product, "product", 64);
        this.releaseTrain = required(releaseTrain, "releaseTrain", 64);
        if (versionCode < 1) throw new IllegalArgumentException("versionCode must be positive");
        this.versionCode = versionCode;
        this.versionName = required(versionName, "versionName", 64);
        if (minimumProtocol < 1 || maximumProtocol < minimumProtocol) {
            throw new IllegalArgumentException("invalid companion protocol range");
        }
        this.minimumProtocol = minimumProtocol;
        this.maximumProtocol = maximumProtocol;
    }

    public static NativeCompanionIdentity current() {
        return new NativeCompanionIdentity(
                ControlledReleaseIdentity.PRODUCT,
                ControlledReleaseIdentity.RELEASE_TRAIN,
                ControlledReleaseIdentity.VERSION_CODE,
                ControlledReleaseIdentity.VERSION_NAME,
                ControlledReleaseIdentity.COMPANION_PROTOCOL,
                ControlledReleaseIdentity.COMPANION_PROTOCOL);
    }

    private NativeCompanionIdentity(Parcel in) {
        this(in.readString(), in.readString(), in.readInt(), in.readString(),
                in.readInt(), in.readInt());
    }

    public String product() { return product; }
    public String releaseTrain() { return releaseTrain; }
    public int versionCode() { return versionCode; }
    public String versionName() { return versionName; }
    public int minimumProtocol() { return minimumProtocol; }
    public int maximumProtocol() { return maximumProtocol; }
    public boolean supportsProtocol(int protocol) {
        return protocol >= minimumProtocol && protocol <= maximumProtocol;
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(product);
        out.writeString(releaseTrain);
        out.writeInt(versionCode);
        out.writeString(versionName);
        out.writeInt(minimumProtocol);
        out.writeInt(maximumProtocol);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<NativeCompanionIdentity> CREATOR = new Creator<>() {
        @Override public NativeCompanionIdentity createFromParcel(Parcel in) {
            return new NativeCompanionIdentity(in);
        }

        @Override public NativeCompanionIdentity[] newArray(int size) {
            return new NativeCompanionIdentity[size];
        }
    };

    private static String required(String value, String name, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        if (normalized.length() > maximum) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }
}
