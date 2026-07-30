package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stable virtual network, capability and link-property descriptor. */
public final class VirtualNetworkSnapshot implements Parcelable {
    public static final String WIFI = "WIFI";
    public static final String CELLULAR = "CELLULAR";
    public static final String ETHERNET = "ETHERNET";
    public static final String BLUETOOTH = "BLUETOOTH";
    public static final String VPN = "VPN";
    public static final String NONE = "NONE";

    private final int networkId;
    private final String transport;
    private final boolean connected;
    private final boolean validated;
    private final boolean metered;
    private final boolean roaming;
    private final boolean captivePortal;
    private final String interfaceName;
    private final int mtu;
    private final int downstreamKbps;
    private final int upstreamKbps;
    private final List<String> addresses;
    private final List<String> dnsServers;
    private final List<String> routes;
    private final String domains;

    public VirtualNetworkSnapshot(int networkId, String transport, boolean connected,
            boolean validated, boolean metered, boolean roaming, boolean captivePortal,
            String interfaceName, int mtu, int downstreamKbps, int upstreamKbps,
            List<String> addresses, List<String> dnsServers, List<String> routes,
            String domains) {
        this.networkId = ContractChecks.nonNegative(networkId, "networkId");
        this.transport = transport(transport);
        this.connected = connected;
        this.validated = validated;
        this.metered = metered;
        this.roaming = roaming;
        this.captivePortal = captivePortal;
        this.interfaceName = ContractChecks.optionalText(interfaceName, "interfaceName", 64);
        if (mtu < 0 || mtu > 65_535 || downstreamKbps < 0 || downstreamKbps > 10_000_000
                || upstreamKbps < 0 || upstreamKbps > 10_000_000) {
            throw new IllegalArgumentException("network metrics are invalid");
        }
        this.mtu = mtu;
        this.downstreamKbps = downstreamKbps;
        this.upstreamKbps = upstreamKbps;
        this.addresses = strings(addresses, "addresses", 32, 128);
        this.dnsServers = ipLiterals(dnsServers, "dnsServers", 16);
        this.routes = strings(routes, "routes", 64, 256);
        this.domains = ContractChecks.optionalText(domains, "domains", 512);
    }

    private VirtualNetworkSnapshot(Parcel in) {
        this(in.readInt(), in.readString(), in.readInt() != 0, in.readInt() != 0,
                in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.readString(), in.readInt(), in.readInt(), in.readInt(),
                in.createStringArrayList(), in.createStringArrayList(),
                in.createStringArrayList(), in.readString());
    }

    public int networkId() { return networkId; }
    public String transport() { return transport; }
    public boolean connected() { return connected; }
    public boolean validated() { return validated; }
    public boolean metered() { return metered; }
    public boolean roaming() { return roaming; }
    public boolean captivePortal() { return captivePortal; }
    public String interfaceName() { return interfaceName; }
    public int mtu() { return mtu; }
    public int downstreamKbps() { return downstreamKbps; }
    public int upstreamKbps() { return upstreamKbps; }
    public List<String> addresses() { return addresses; }
    public List<String> dnsServers() { return dnsServers; }
    public List<String> routes() { return routes; }
    public String domains() { return domains; }

    static String transport(String value) {
        String normalized = ContractChecks.requiredText(value, "transport", 32)
                .toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case WIFI, CELLULAR, ETHERNET, BLUETOOTH, VPN, NONE -> normalized;
            default -> throw new IllegalArgumentException("transport is invalid");
        };
    }

    static List<String> strings(List<String> source, String field, int maximum, int maxChars) {
        List<String> values = source == null ? List.of() : new ArrayList<>(source);
        if (values.size() > maximum || values.contains(null)) {
            throw new IllegalArgumentException(field + " are invalid");
        }
        ArrayList<String> checked = new ArrayList<>(values.size());
        for (String value : values) checked.add(ContractChecks.requiredText(value, field, maxChars));
        return Collections.unmodifiableList(checked);
    }

    static List<String> ipLiterals(List<String> source, String field, int maximum) {
        List<String> values = strings(source, field, maximum, 128);
        for (String value : values) {
            if (!isIpLiteral(value)) throw new IllegalArgumentException(field + " must contain IP literals");
        }
        return values;
    }

    private static boolean isIpLiteral(String value) {
        if (value.indexOf(':') >= 0) return value.matches("[0-9A-Fa-f:.]+");
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            int number = 0;
            for (int index = 0; index < part.length(); index++) {
                char ch = part.charAt(index);
                if (ch < '0' || ch > '9') return false;
                number = number * 10 + ch - '0';
            }
            if (number > 255) return false;
        }
        return true;
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(networkId); out.writeString(transport); out.writeInt(connected ? 1 : 0);
        out.writeInt(validated ? 1 : 0); out.writeInt(metered ? 1 : 0);
        out.writeInt(roaming ? 1 : 0); out.writeInt(captivePortal ? 1 : 0);
        out.writeString(interfaceName); out.writeInt(mtu); out.writeInt(downstreamKbps);
        out.writeInt(upstreamKbps); out.writeStringList(addresses); out.writeStringList(dnsServers);
        out.writeStringList(routes); out.writeString(domains);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualNetworkSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualNetworkSnapshot createFromParcel(Parcel in) { return new VirtualNetworkSnapshot(in); }
        @Override public VirtualNetworkSnapshot[] newArray(int size) { return new VirtualNetworkSnapshot[size]; }
    };
}
