package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Virtual HTTP proxy/PAC policy. */
public final class VirtualProxyProfileSnapshot implements Parcelable {
    public static final String NONE = "NONE";
    public static final String STATIC = "STATIC";
    public static final String PAC = "PAC";
    private final String mode;
    private final String type;
    private final String host;
    private final int port;
    private final List<String> exclusionList;
    private final String pacUrl;
    private final boolean allowGuestOverride;

    public VirtualProxyProfileSnapshot(String mode, String type, String host, int port,
            List<String> exclusionList, String pacUrl, boolean allowGuestOverride) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        String normalized = ContractChecks.requiredText(type, "proxyType", 16).toUpperCase(java.util.Locale.ROOT);
        if (!NONE.equals(normalized) && !STATIC.equals(normalized) && !PAC.equals(normalized)) {
            throw new IllegalArgumentException("proxyType is invalid");
        }
        this.type = normalized;
        this.host = ContractChecks.optionalText(host, "proxyHost", 253);
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("proxyPort is invalid");
        this.port = port;
        this.exclusionList = VirtualNetworkSnapshot.strings(exclusionList, "proxyExclusions", 64, 253);
        this.pacUrl = ContractChecks.optionalText(pacUrl, "pacUrl", 2048);
        if (STATIC.equals(normalized) && (this.host.isEmpty() || port == 0)) {
            throw new IllegalArgumentException("static proxy host/port are required");
        }
        if (PAC.equals(normalized) && this.pacUrl.isEmpty()) throw new IllegalArgumentException("pacUrl is required");
        this.allowGuestOverride = allowGuestOverride;
    }
    private VirtualProxyProfileSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readInt(),
                in.createStringArrayList(), in.readString(), in.readInt() != 0);
    }
    public String mode() { return mode; }
    public String type() { return type; }
    public String host() { return host; }
    public int port() { return port; }
    public List<String> exclusionList() { return exclusionList; }
    public String pacUrl() { return pacUrl; }
    public boolean allowGuestOverride() { return allowGuestOverride; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeString(type); out.writeString(host); out.writeInt(port);
        out.writeStringList(exclusionList); out.writeString(pacUrl); out.writeInt(allowGuestOverride ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualProxyProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualProxyProfileSnapshot createFromParcel(Parcel in) { return new VirtualProxyProfileSnapshot(in); }
        @Override public VirtualProxyProfileSnapshot[] newArray(int size) { return new VirtualProxyProfileSnapshot[size]; }
    };
}
