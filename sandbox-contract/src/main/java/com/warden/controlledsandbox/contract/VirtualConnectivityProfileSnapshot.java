package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Java connectivity state and callback policy. */
public final class VirtualConnectivityProfileSnapshot implements Parcelable {
    private final String mode;
    private final int defaultNetworkId;
    private final boolean airplaneMode;
    private final boolean backgroundRestricted;
    private final int maximumCallbacks;
    private final List<VirtualNetworkSnapshot> networks;

    public VirtualConnectivityProfileSnapshot(String mode, int defaultNetworkId,
            boolean airplaneMode, boolean backgroundRestricted, int maximumCallbacks,
            List<VirtualNetworkSnapshot> networks) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        if (defaultNetworkId < -1) throw new IllegalArgumentException("defaultNetworkId is invalid");
        this.defaultNetworkId = defaultNetworkId;
        this.airplaneMode = airplaneMode;
        this.backgroundRestricted = backgroundRestricted;
        if (maximumCallbacks < 0 || maximumCallbacks > 128) {
            throw new IllegalArgumentException("maximumCallbacks is invalid");
        }
        this.maximumCallbacks = maximumCallbacks;
        List<VirtualNetworkSnapshot> copy = networks == null ? List.of() : new ArrayList<>(networks);
        if (copy.size() > 32 || copy.contains(null)) throw new IllegalArgumentException("networks are invalid");
        java.util.HashSet<Integer> ids = new java.util.HashSet<>();
        for (VirtualNetworkSnapshot network : copy) {
            if (!ids.add(network.networkId())) throw new IllegalArgumentException("duplicate networkId");
        }
        if (defaultNetworkId >= 0 && copy.stream().noneMatch(value -> value.networkId() == defaultNetworkId)) {
            throw new IllegalArgumentException("default network is missing");
        }
        this.networks = Collections.unmodifiableList(copy);
    }
    private VirtualConnectivityProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt(), in.readInt() != 0, in.readInt() != 0,
                in.readInt(), in.createTypedArrayList(VirtualNetworkSnapshot.CREATOR));
    }
    public String mode() { return mode; }
    public int defaultNetworkId() { return defaultNetworkId; }
    public boolean airplaneMode() { return airplaneMode; }
    public boolean backgroundRestricted() { return backgroundRestricted; }
    public int maximumCallbacks() { return maximumCallbacks; }
    public List<VirtualNetworkSnapshot> networks() { return networks; }
    public VirtualNetworkSnapshot defaultNetwork() { return network(defaultNetworkId); }
    public VirtualNetworkSnapshot network(int networkId) {
        for (VirtualNetworkSnapshot network : networks) if (network.networkId() == networkId) return network;
        return null;
    }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeInt(defaultNetworkId); out.writeInt(airplaneMode ? 1 : 0);
        out.writeInt(backgroundRestricted ? 1 : 0); out.writeInt(maximumCallbacks);
        out.writeTypedList(networks);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualConnectivityProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualConnectivityProfileSnapshot createFromParcel(Parcel in) { return new VirtualConnectivityProfileSnapshot(in); }
        @Override public VirtualConnectivityProfileSnapshot[] newArray(int size) { return new VirtualConnectivityProfileSnapshot[size]; }
    };
}
