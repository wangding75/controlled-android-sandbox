package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Complete revision-independent virtual device-service profile for one package/user scope. */
public final class VirtualDeviceServiceProfileSnapshot implements Parcelable {
    private final long policyVersion;
    private final long updatedAtMs;
    private final VirtualLocationProfileSnapshot location;
    private final VirtualDeviceIdentitySnapshot identity;
    private final VirtualTelephonyProfileSnapshot telephony;
    private final VirtualWifiProfileSnapshot wifi;
    private final VirtualBluetoothProfileSnapshot bluetooth;
    private final VirtualSensorProfileSnapshot sensors;

    public VirtualDeviceServiceProfileSnapshot(long policyVersion, long updatedAtMs,
            VirtualLocationProfileSnapshot location, VirtualDeviceIdentitySnapshot identity,
            VirtualTelephonyProfileSnapshot telephony, VirtualWifiProfileSnapshot wifi,
            VirtualBluetoothProfileSnapshot bluetooth, VirtualSensorProfileSnapshot sensors) {
        if (policyVersion < 1L || updatedAtMs < 0L) {
            throw new IllegalArgumentException("profile version/time is invalid");
        }
        this.policyVersion = policyVersion;
        this.updatedAtMs = updatedAtMs;
        this.location = java.util.Objects.requireNonNull(location, "location");
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.telephony = java.util.Objects.requireNonNull(telephony, "telephony");
        this.wifi = java.util.Objects.requireNonNull(wifi, "wifi");
        this.bluetooth = java.util.Objects.requireNonNull(bluetooth, "bluetooth");
        this.sensors = java.util.Objects.requireNonNull(sensors, "sensors");
    }

    private VirtualDeviceServiceProfileSnapshot(Parcel in) {
        this(in.readLong(), in.readLong(),
                in.readParcelable(VirtualLocationProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualDeviceIdentitySnapshot.class.getClassLoader()),
                in.readParcelable(VirtualTelephonyProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualWifiProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualBluetoothProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualSensorProfileSnapshot.class.getClassLoader()));
    }

    public long policyVersion() { return policyVersion; }
    public long updatedAtMs() { return updatedAtMs; }
    public VirtualLocationProfileSnapshot location() { return location; }
    public VirtualDeviceIdentitySnapshot identity() { return identity; }
    public VirtualTelephonyProfileSnapshot telephony() { return telephony; }
    public VirtualWifiProfileSnapshot wifi() { return wifi; }
    public VirtualBluetoothProfileSnapshot bluetooth() { return bluetooth; }
    public VirtualSensorProfileSnapshot sensors() { return sensors; }

    public VirtualDeviceServiceProfileSnapshot withVersion(long version, long updatedAt) {
        return new VirtualDeviceServiceProfileSnapshot(version, updatedAt, location, identity,
                telephony, wifi, bluetooth, sensors);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(policyVersion); out.writeLong(updatedAtMs);
        out.writeParcelable(location, flags); out.writeParcelable(identity, flags);
        out.writeParcelable(telephony, flags); out.writeParcelable(wifi, flags);
        out.writeParcelable(bluetooth, flags); out.writeParcelable(sensors, flags);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualDeviceServiceProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualDeviceServiceProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualDeviceServiceProfileSnapshot(in);
        }
        @Override public VirtualDeviceServiceProfileSnapshot[] newArray(int size) {
            return new VirtualDeviceServiceProfileSnapshot[size];
        }
    };
}
