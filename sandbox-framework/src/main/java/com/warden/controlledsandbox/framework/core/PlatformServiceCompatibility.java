package com.warden.controlledsandbox.framework.core;

import android.os.Build;

/**
 * Central API-contract decisions for platform services that are not application-facing on a
 * supported Android release.  This is deliberately kept separate from service semantics so a
 * platform restriction cannot turn into a Host fallback or a scattered SDK check.
 */
public final class PlatformServiceCompatibility {
    private PlatformServiceCompatibility() { }

    /**
     * Android 13 exposes these Binder names only to the system API domain.  An ordinary
     * application process cannot discover them through ServiceManager on the AOSP/Google API33
     * image, even though the services are registered for system components.  Each caller must
     * use its supported application-facing facade when one exists.
     */
    public static boolean isApplicationRestricted(String logicalCapability) {
        return Build.VERSION.SDK_INT == 33
                && ("wifiScanner".equals(logicalCapability)
                || "dnsResolver".equals(logicalCapability));
    }

    public static String restrictionReason(String logicalCapability) {
        if (!isApplicationRestricted(logicalCapability)) return "";
        if ("wifiScanner".equals(logicalCapability)) {
            return "EXPECTED_PLATFORM_BEHAVIOR:API33 system-api wifiscanner is not discoverable by "
                    + "the untrusted application domain; WifiManager remains the supported app API";
        }
        if ("dnsResolver".equals(logicalCapability)) {
            return "EXPECTED_PLATFORM_BEHAVIOR:API33 system-api dnsresolver is not discoverable by "
                    + "the untrusted application domain; DnsResolver native facade remains the "
                    + "supported app API";
        }
        return "";
    }
}
