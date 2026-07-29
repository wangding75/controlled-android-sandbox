package com.warden.controlledsandbox.nativebridge;

/** Immutable Guest-visible network identity passed to the native policy boundary. */
public final class NativeNetworkIdentity {
    private final String hostname;
    private final String interfaceName;
    private final String ipv4Address;
    private final String ipv6Address;
    private final String proxyHost;
    private final int proxyPort;
    private final boolean cleartextPermitted;

    public NativeNetworkIdentity(String hostname, String interfaceName,
                                 String ipv4Address, String ipv6Address,
                                 String proxyHost, int proxyPort,
                                 boolean cleartextPermitted) {
        this.hostname = required(hostname, "hostname", 253);
        this.interfaceName = required(interfaceName, "interfaceName", 15);
        this.ipv4Address = required(ipv4Address, "ipv4Address", 64);
        this.ipv6Address = required(ipv6Address, "ipv6Address", 64);
        this.proxyHost = proxyHost == null ? "" : proxyHost.trim();
        if (this.proxyHost.length() > 253) throw new IllegalArgumentException("proxyHost is too long");
        if (proxyPort < 0 || proxyPort > 65535) throw new IllegalArgumentException("proxyPort out of range");
        this.proxyPort = proxyPort;
        this.cleartextPermitted = cleartextPermitted;
    }

    public static NativeNetworkIdentity isolated(String packageName, int virtualUserId) {
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        String suffix = Integer.toString(Math.floorMod(virtualUserId, 250));
        return new NativeNetworkIdentity(packageName + ".sandbox", "vnet0",
                "10.64." + suffix + ".2", "fd00::" + (virtualUserId + 2), "", 0, true);
    }

    public String hostname() { return hostname; }
    public String interfaceName() { return interfaceName; }
    public String ipv4Address() { return ipv4Address; }
    public String ipv6Address() { return ipv6Address; }
    public String proxyHost() { return proxyHost; }
    public int proxyPort() { return proxyPort; }
    public boolean cleartextPermitted() { return cleartextPermitted; }

    private static String required(String value, String name, int maxLength) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }
}
