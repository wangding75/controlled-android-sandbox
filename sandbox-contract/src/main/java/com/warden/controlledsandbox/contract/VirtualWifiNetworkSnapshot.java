package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Locale;

/** One synthetic Wi-Fi scan result. */
public final class VirtualWifiNetworkSnapshot implements Parcelable {
    private final String ssid;
    private final String bssid;
    private final String capabilities;
    private final int frequencyMhz;
    private final int rssi;
    private final boolean hidden;

    public VirtualWifiNetworkSnapshot(String ssid, String bssid, String capabilities,
            int frequencyMhz, int rssi, boolean hidden) {
        this.ssid = ContractChecks.optionalText(ssid, "ssid", 64);
        this.bssid = mac(bssid, "bssid");
        this.capabilities = ContractChecks.optionalText(capabilities, "capabilities", 256);
        if (frequencyMhz < 0 || frequencyMhz > 100_000) {
            throw new IllegalArgumentException("frequencyMhz is invalid");
        }
        if (rssi < -200 || rssi > 100) throw new IllegalArgumentException("rssi is invalid");
        this.frequencyMhz = frequencyMhz;
        this.rssi = rssi;
        this.hidden = hidden;
    }

    private VirtualWifiNetworkSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readInt(), in.readInt(), in.readInt() != 0);
    }

    public String ssid() { return ssid; }
    public String bssid() { return bssid; }
    public String capabilities() { return capabilities; }
    public int frequencyMhz() { return frequencyMhz; }
    public int rssi() { return rssi; }
    public boolean hidden() { return hidden; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(ssid); out.writeString(bssid); out.writeString(capabilities);
        out.writeInt(frequencyMhz); out.writeInt(rssi); out.writeInt(hidden ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualWifiNetworkSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualWifiNetworkSnapshot createFromParcel(Parcel in) {
            return new VirtualWifiNetworkSnapshot(in);
        }
        @Override public VirtualWifiNetworkSnapshot[] newArray(int size) {
            return new VirtualWifiNetworkSnapshot[size];
        }
    };

    static String mac(String value, String field) {
        String normalized = ContractChecks.optionalText(value, field, 17).trim().toUpperCase(Locale.ROOT);
        if (!normalized.isEmpty() && !normalized.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
