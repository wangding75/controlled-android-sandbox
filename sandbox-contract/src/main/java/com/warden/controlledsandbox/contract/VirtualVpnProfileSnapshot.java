package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Virtual VPN status, lockdown and bounded source-side session policy. */
public final class VirtualVpnProfileSnapshot implements Parcelable {
    public static final String DISCONNECTED = "DISCONNECTED";
    public static final String CONNECTING = "CONNECTING";
    public static final String CONNECTED = "CONNECTED";
    private final String mode;
    private final String state;
    private final String alwaysOnPackage;
    private final boolean lockdown;
    private final List<String> lockdownAllowlist;
    private final boolean allowProvisioning;
    private final boolean allowEstablish;
    private final int maximumSessions;
    private final String interfaceName;
    private final List<String> addresses;
    private final List<String> routes;
    private final List<String> dnsServers;

    public VirtualVpnProfileSnapshot(String mode, String state, String alwaysOnPackage,
            boolean lockdown, List<String> lockdownAllowlist, boolean allowProvisioning,
            boolean allowEstablish, int maximumSessions, String interfaceName,
            List<String> addresses, List<String> routes, List<String> dnsServers) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        String normalized = ContractChecks.requiredText(state, "vpnState", 32).toUpperCase(java.util.Locale.ROOT);
        if (!DISCONNECTED.equals(normalized) && !CONNECTING.equals(normalized) && !CONNECTED.equals(normalized)) {
            throw new IllegalArgumentException("vpnState is invalid");
        }
        this.state = normalized;
        this.alwaysOnPackage = ContractChecks.optionalText(alwaysOnPackage, "alwaysOnPackage", 255);
        this.lockdown = lockdown;
        this.lockdownAllowlist = VirtualNetworkSnapshot.strings(lockdownAllowlist, "vpnAllowlist", 128, 255);
        this.allowProvisioning = allowProvisioning;
        this.allowEstablish = allowEstablish;
        if (maximumSessions < 0 || maximumSessions > 8) throw new IllegalArgumentException("maximumSessions is invalid");
        this.maximumSessions = maximumSessions;
        this.interfaceName = ContractChecks.optionalText(interfaceName, "vpnInterface", 64);
        this.addresses = VirtualNetworkSnapshot.strings(addresses, "vpnAddresses", 32, 128);
        this.routes = VirtualNetworkSnapshot.strings(routes, "vpnRoutes", 64, 256);
        this.dnsServers = VirtualNetworkSnapshot.ipLiterals(dnsServers, "vpnDnsServers", 16);
        if (allowEstablish && maximumSessions == 0) throw new IllegalArgumentException("VPN sessions must be bounded");
    }
    private VirtualVpnProfileSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readInt() != 0,
                in.createStringArrayList(), in.readInt() != 0, in.readInt() != 0,
                in.readInt(), in.readString(), in.createStringArrayList(),
                in.createStringArrayList(), in.createStringArrayList());
    }
    public String mode() { return mode; }
    public String state() { return state; }
    public String alwaysOnPackage() { return alwaysOnPackage; }
    public boolean lockdown() { return lockdown; }
    public List<String> lockdownAllowlist() { return lockdownAllowlist; }
    public boolean allowProvisioning() { return allowProvisioning; }
    public boolean allowEstablish() { return allowEstablish; }
    public int maximumSessions() { return maximumSessions; }
    public String interfaceName() { return interfaceName; }
    public List<String> addresses() { return addresses; }
    public List<String> routes() { return routes; }
    public List<String> dnsServers() { return dnsServers; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeString(state); out.writeString(alwaysOnPackage);
        out.writeInt(lockdown ? 1 : 0); out.writeStringList(lockdownAllowlist);
        out.writeInt(allowProvisioning ? 1 : 0); out.writeInt(allowEstablish ? 1 : 0);
        out.writeInt(maximumSessions); out.writeString(interfaceName);
        out.writeStringList(addresses); out.writeStringList(routes); out.writeStringList(dnsServers);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualVpnProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualVpnProfileSnapshot createFromParcel(Parcel in) { return new VirtualVpnProfileSnapshot(in); }
        @Override public VirtualVpnProfileSnapshot[] newArray(int size) { return new VirtualVpnProfileSnapshot[size]; }
    };
}
