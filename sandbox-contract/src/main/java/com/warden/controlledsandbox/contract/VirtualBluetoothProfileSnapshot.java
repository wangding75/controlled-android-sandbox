package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Virtual local Bluetooth adapter and bounded remote-device projection. */
public final class VirtualBluetoothProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean enabled;
    private final int state;
    private final String name;
    private final String address;
    private final boolean discovering;
    private final List<VirtualBluetoothDeviceSnapshot> bondedDevices;
    private final List<VirtualBluetoothDeviceSnapshot> scanResults;

    public VirtualBluetoothProfileSnapshot(String mode, boolean enabled, int state,
            String name, String address, boolean discovering,
            List<VirtualBluetoothDeviceSnapshot> bondedDevices,
            List<VirtualBluetoothDeviceSnapshot> scanResults) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.enabled = enabled;
        if (state < 0 || state > 32) throw new IllegalArgumentException("state is invalid");
        this.state = state;
        this.name = ContractChecks.optionalText(name, "name", 96);
        this.address = VirtualWifiNetworkSnapshot.mac(address, "address");
        this.discovering = discovering;
        this.bondedDevices = devices(bondedDevices, "bondedDevices", 64);
        this.scanResults = devices(scanResults, "scanResults", 128);
    }

    private VirtualBluetoothProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt(), in.readString(), in.readString(),
                in.readInt() != 0, in.createTypedArrayList(VirtualBluetoothDeviceSnapshot.CREATOR),
                in.createTypedArrayList(VirtualBluetoothDeviceSnapshot.CREATOR));
    }

    public String mode() { return mode; }
    public boolean enabled() { return enabled; }
    public int state() { return state; }
    public String name() { return name; }
    public String address() { return address; }
    public boolean discovering() { return discovering; }
    public List<VirtualBluetoothDeviceSnapshot> bondedDevices() { return bondedDevices; }
    public List<VirtualBluetoothDeviceSnapshot> scanResults() { return scanResults; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeInt(enabled ? 1 : 0); out.writeInt(state);
        out.writeString(name); out.writeString(address); out.writeInt(discovering ? 1 : 0);
        out.writeTypedList(bondedDevices); out.writeTypedList(scanResults);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualBluetoothProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualBluetoothProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualBluetoothProfileSnapshot(in);
        }
        @Override public VirtualBluetoothProfileSnapshot[] newArray(int size) {
            return new VirtualBluetoothProfileSnapshot[size];
        }
    };

    private static List<VirtualBluetoothDeviceSnapshot> devices(
            List<VirtualBluetoothDeviceSnapshot> values, String field, int maximum) {
        List<VirtualBluetoothDeviceSnapshot> copy = values == null ? List.of() : new ArrayList<>(values);
        if (copy.size() > maximum || copy.contains(null)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        Set<String> addresses = new LinkedHashSet<>();
        for (VirtualBluetoothDeviceSnapshot value : copy) {
            if (!addresses.add(value.address())) throw new IllegalArgumentException(field + " has duplicate address");
        }
        return Collections.unmodifiableList(copy);
    }
}
