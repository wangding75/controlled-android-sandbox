package com.warden.controlledsandbox.contract;

/** Public error codes and the fixed marker for server-owned Package Service capability epochs. */
public final class PackageAuthorityCapabilityContract {
    public static final String MANAGEMENT_CAPABILITY_REQUIRED =
            "PACKAGE_MANAGEMENT_CAPABILITY_REQUIRED";
    public static final String RUNTIME_CAPABILITY_REQUIRED =
            "PACKAGE_RUNTIME_CAPABILITY_REQUIRED";

    /** Clients do not select or advance capability generations. */
    public static final long SERVER_MANAGED_EPOCH = 0L;

    private PackageAuthorityCapabilityContract() { }
}
