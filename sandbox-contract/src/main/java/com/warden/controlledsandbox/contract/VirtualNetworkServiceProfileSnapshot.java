package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Connectivity, DNS, proxy and VPN profile for one package/virtual-user scope. */
public final class VirtualNetworkServiceProfileSnapshot implements Parcelable {
    private final long policyVersion;
    private final long updatedAtMs;
    private final VirtualConnectivityProfileSnapshot connectivity;
    private final VirtualDnsProfileSnapshot dns;
    private final VirtualProxyProfileSnapshot proxy;
    private final VirtualVpnProfileSnapshot vpn;

    public VirtualNetworkServiceProfileSnapshot(long policyVersion, long updatedAtMs,
            VirtualConnectivityProfileSnapshot connectivity, VirtualDnsProfileSnapshot dns,
            VirtualProxyProfileSnapshot proxy, VirtualVpnProfileSnapshot vpn) {
        if (policyVersion < 1L || updatedAtMs < 0L) throw new IllegalArgumentException("network profile version/time is invalid");
        this.policyVersion = policyVersion;
        this.updatedAtMs = updatedAtMs;
        this.connectivity = java.util.Objects.requireNonNull(connectivity, "connectivity");
        this.dns = java.util.Objects.requireNonNull(dns, "dns");
        this.proxy = java.util.Objects.requireNonNull(proxy, "proxy");
        this.vpn = java.util.Objects.requireNonNull(vpn, "vpn");
    }
    private VirtualNetworkServiceProfileSnapshot(Parcel in) {
        this(in.readLong(), in.readLong(),
                in.readParcelable(VirtualConnectivityProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualDnsProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualProxyProfileSnapshot.class.getClassLoader()),
                in.readParcelable(VirtualVpnProfileSnapshot.class.getClassLoader()));
    }
    public long policyVersion() { return policyVersion; }
    public long updatedAtMs() { return updatedAtMs; }
    public VirtualConnectivityProfileSnapshot connectivity() { return connectivity; }
    public VirtualDnsProfileSnapshot dns() { return dns; }
    public VirtualProxyProfileSnapshot proxy() { return proxy; }
    public VirtualVpnProfileSnapshot vpn() { return vpn; }
    public VirtualNetworkServiceProfileSnapshot withVersion(long version, long updatedAt) {
        return new VirtualNetworkServiceProfileSnapshot(version, updatedAt, connectivity, dns, proxy, vpn);
    }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeLong(policyVersion); out.writeLong(updatedAtMs); out.writeParcelable(connectivity, flags);
        out.writeParcelable(dns, flags); out.writeParcelable(proxy, flags); out.writeParcelable(vpn, flags);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualNetworkServiceProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualNetworkServiceProfileSnapshot createFromParcel(Parcel in) { return new VirtualNetworkServiceProfileSnapshot(in); }
        @Override public VirtualNetworkServiceProfileSnapshot[] newArray(int size) { return new VirtualNetworkServiceProfileSnapshot[size]; }
    };
}
