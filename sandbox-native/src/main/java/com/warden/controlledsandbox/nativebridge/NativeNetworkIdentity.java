package com.warden.controlledsandbox.nativebridge;

import java.util.Arrays;
import java.util.Locale;

/** Immutable Guest-visible network and Connectivity identity passed to the native policy boundary. */
public final class NativeNetworkIdentity {
    public static final String TRANSPORT_WIFI = "WIFI";
    public static final String TRANSPORT_CELLULAR = "CELLULAR";
    public static final String TRANSPORT_ETHERNET = "ETHERNET";
    public static final String TRANSPORT_VPN = "VPN";

    private final String hostname;
    private final String interfaceName;
    private final String ipv4Address;
    private final String ipv6Address;
    private final String proxyHost;
    private final int proxyPort;
    private final boolean cleartextPermitted;
    private final int networkId;
    private final String transport;
    private final boolean vpnActive;
    private final boolean metered;
    private final boolean validated;
    private final int mtu;
    private final String privateDnsServerName;
    private final String[] dnsServers;

    /** Backward-compatible constructor retained for existing runtime callers. */
    public NativeNetworkIdentity(String hostname, String interfaceName,
                                 String ipv4Address, String ipv6Address,
                                 String proxyHost, int proxyPort,
                                 boolean cleartextPermitted) {
        this(hostname, interfaceName, ipv4Address, ipv6Address, proxyHost, proxyPort,
                cleartextPermitted, 100, TRANSPORT_WIFI, false, false, true, 1500, "",
                new String[0]);
    }

    public NativeNetworkIdentity(String hostname, String interfaceName,
                                 String ipv4Address, String ipv6Address,
                                 String proxyHost, int proxyPort,
                                 boolean cleartextPermitted, int networkId,
                                 String transport, boolean vpnActive,
                                 boolean metered, boolean validated, int mtu,
                                 String privateDnsServerName, String[] dnsServers) {
        this.hostname = required(hostname, "hostname", 253);
        this.interfaceName = required(interfaceName, "interfaceName", 15);
        this.ipv4Address = required(ipv4Address, "ipv4Address", 64);
        this.ipv6Address = required(ipv6Address, "ipv6Address", 64);
        this.proxyHost = optional(proxyHost, "proxyHost", 253);
        if (proxyPort < 0 || proxyPort > 65535) throw new IllegalArgumentException("proxyPort out of range");
        this.proxyPort = proxyPort;
        this.cleartextPermitted = cleartextPermitted;
        if (networkId < 1) throw new IllegalArgumentException("networkId must be positive");
        this.networkId = networkId;
        this.transport = normalizeTransport(transport);
        this.vpnActive = vpnActive;
        this.metered = metered;
        this.validated = validated;
        if (mtu < 576 || mtu > 65535) throw new IllegalArgumentException("mtu out of range");
        this.mtu = mtu;
        this.privateDnsServerName = optional(privateDnsServerName, "privateDnsServerName", 253);
        this.dnsServers = normalizeDnsServers(dnsServers);
    }

    public static NativeNetworkIdentity isolated(String packageName, int virtualUserId) {
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        String suffix = Integer.toString(Math.floorMod(virtualUserId, 250));
        int networkId = 10_000 + Math.floorMod(virtualUserId, 50_000);
        return new NativeNetworkIdentity(packageName + ".sandbox", "vnet0",
                "10.64." + suffix + ".2", "fd00::" + (virtualUserId + 2), "", 0, true,
                networkId, TRANSPORT_WIFI, false, false, true, 1500, "", new String[0]);
    }

    public String hostname() { return hostname; }
    public String interfaceName() { return interfaceName; }
    public String ipv4Address() { return ipv4Address; }
    public String ipv6Address() { return ipv6Address; }
    public String proxyHost() { return proxyHost; }
    public int proxyPort() { return proxyPort; }
    public boolean cleartextPermitted() { return cleartextPermitted; }
    public int networkId() { return networkId; }
    public String transport() { return transport; }
    public boolean vpnActive() { return vpnActive; }
    public boolean metered() { return metered; }
    public boolean validated() { return validated; }
    public int mtu() { return mtu; }
    public String privateDnsServerName() { return privateDnsServerName; }
    public String[] dnsServers() { return dnsServers.clone(); }

    private static String required(String value, String name, int maxLength) {
        String normalized = optional(value, name, maxLength);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }

    private static String optional(String value, String name, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }

    private static String normalizeTransport(String value) {
        String normalized = required(value, "transport", 16).toUpperCase(Locale.ROOT);
        if (!Arrays.asList(TRANSPORT_WIFI, TRANSPORT_CELLULAR, TRANSPORT_ETHERNET, TRANSPORT_VPN)
                .contains(normalized)) {
            throw new IllegalArgumentException("unsupported transport " + value);
        }
        return normalized;
    }

    private static String[] normalizeDnsServers(String[] values) {
        if (values == null || values.length == 0) return new String[0];
        if (values.length > 8) throw new IllegalArgumentException("too many dnsServers");
        String[] normalized = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            normalized[i] = required(values[i], "dnsServers[" + i + "]", 64);
        }
        return normalized;
    }
}
