package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Synthetic bonded or discovered Bluetooth device. */
public final class VirtualBluetoothDeviceSnapshot implements Parcelable {
    private final String address;
    private final String name;
    private final int type;
    private final int bondState;
    private final int rssi;
    private final List<String> serviceUuids;

    public VirtualBluetoothDeviceSnapshot(String address, String name, int type,
            int bondState, int rssi, List<String> serviceUuids) {
        this.address = VirtualWifiNetworkSnapshot.mac(address, "address");
        if (this.address.isEmpty()) throw new IllegalArgumentException("address is required");
        this.name = ContractChecks.optionalText(name, "name", 96);
        if (type < 0 || type > 16) throw new IllegalArgumentException("type is invalid");
        if (bondState < 0 || bondState > 32) throw new IllegalArgumentException("bondState is invalid");
        if (rssi < -200 || rssi > 100) throw new IllegalArgumentException("rssi is invalid");
        this.type = type;
        this.bondState = bondState;
        this.rssi = rssi;
        List<String> copy = serviceUuids == null ? List.of() : new ArrayList<>(serviceUuids);
        if (copy.size() > 32) throw new IllegalArgumentException("serviceUuids limit exceeded");
        for (int index = 0; index < copy.size(); index++) {
            copy.set(index, ContractChecks.requiredText(copy.get(index), "serviceUuid", 64));
        }
        this.serviceUuids = Collections.unmodifiableList(copy);
    }

    private VirtualBluetoothDeviceSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readInt(), in.readInt(), in.readInt(),
                in.createStringArrayList());
    }

    public String address() { return address; }
    public String name() { return name; }
    public int type() { return type; }
    public int bondState() { return bondState; }
    public int rssi() { return rssi; }
    public List<String> serviceUuids() { return serviceUuids; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(address); out.writeString(name); out.writeInt(type);
        out.writeInt(bondState); out.writeInt(rssi); out.writeStringList(serviceUuids);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualBluetoothDeviceSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualBluetoothDeviceSnapshot createFromParcel(Parcel in) {
            return new VirtualBluetoothDeviceSnapshot(in);
        }
        @Override public VirtualBluetoothDeviceSnapshot[] newArray(int size) {
            return new VirtualBluetoothDeviceSnapshot[size];
        }
    };
}
