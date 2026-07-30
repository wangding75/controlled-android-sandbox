package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Virtual Wi-Fi identity, connection information and bounded scan results. */
public final class VirtualWifiProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean enabled;
    private final String ssid;
    private final String bssid;
    private final String macAddress;
    private final int ipv4Address;
    private final int networkId;
    private final int linkSpeedMbps;
    private final int rssi;
    private final int frequencyMhz;
    private final boolean metered;
    private final boolean hiddenSsid;
    private final List<VirtualWifiNetworkSnapshot> scanResults;

    public VirtualWifiProfileSnapshot(String mode, boolean enabled, String ssid, String bssid,
            String macAddress, int ipv4Address, int networkId, int linkSpeedMbps, int rssi,
            int frequencyMhz, boolean metered, boolean hiddenSsid,
            List<VirtualWifiNetworkSnapshot> scanResults) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.enabled = enabled;
        this.ssid = ContractChecks.optionalText(ssid, "ssid", 64);
        this.bssid = VirtualWifiNetworkSnapshot.mac(bssid, "bssid");
        this.macAddress = VirtualWifiNetworkSnapshot.mac(macAddress, "macAddress");
        this.ipv4Address = ipv4Address;
        if (networkId < -1) throw new IllegalArgumentException("networkId is invalid");
        if (linkSpeedMbps < 0 || linkSpeedMbps > 100_000) {
            throw new IllegalArgumentException("linkSpeedMbps is invalid");
        }
        if (rssi < -200 || rssi > 100) throw new IllegalArgumentException("rssi is invalid");
        if (frequencyMhz < 0 || frequencyMhz > 100_000) {
            throw new IllegalArgumentException("frequencyMhz is invalid");
        }
        this.networkId = networkId;
        this.linkSpeedMbps = linkSpeedMbps;
        this.rssi = rssi;
        this.frequencyMhz = frequencyMhz;
        this.metered = metered;
        this.hiddenSsid = hiddenSsid;
        List<VirtualWifiNetworkSnapshot> copy = scanResults == null ? List.of() : new ArrayList<>(scanResults);
        if (copy.size() > 128 || copy.contains(null)) throw new IllegalArgumentException("scanResults are invalid");
        this.scanResults = Collections.unmodifiableList(copy);
    }

    private VirtualWifiProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readString(), in.readString(), in.readString(),
                in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                in.readInt() != 0, in.readInt() != 0,
                in.createTypedArrayList(VirtualWifiNetworkSnapshot.CREATOR));
    }

    public String mode() { return mode; }
    public boolean enabled() { return enabled; }
    public String ssid() { return ssid; }
    public String bssid() { return bssid; }
    public String macAddress() { return macAddress; }
    public int ipv4Address() { return ipv4Address; }
    public int networkId() { return networkId; }
    public int linkSpeedMbps() { return linkSpeedMbps; }
    public int rssi() { return rssi; }
    public int frequencyMhz() { return frequencyMhz; }
    public boolean metered() { return metered; }
    public boolean hiddenSsid() { return hiddenSsid; }
    public List<VirtualWifiNetworkSnapshot> scanResults() { return scanResults; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeInt(enabled ? 1 : 0); out.writeString(ssid);
        out.writeString(bssid); out.writeString(macAddress); out.writeInt(ipv4Address);
        out.writeInt(networkId); out.writeInt(linkSpeedMbps); out.writeInt(rssi);
        out.writeInt(frequencyMhz); out.writeInt(metered ? 1 : 0); out.writeInt(hiddenSsid ? 1 : 0);
        out.writeTypedList(scanResults);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualWifiProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualWifiProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualWifiProfileSnapshot(in);
        }
        @Override public VirtualWifiProfileSnapshot[] newArray(int size) {
            return new VirtualWifiProfileSnapshot[size];
        }
    };
}
