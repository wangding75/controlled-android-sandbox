package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import java.util.Map;

/** Fail-closed readiness gate for compatibility services. */
final class CompatibilityProxyReadiness {
    private CompatibilityProxyReadiness() { }
    static void require(Map<String,Boolean> installed, VirtualCompatibilityProfileSnapshot profile,
            boolean nativePolicyConfigured) {
        if (profile == null) throw new IllegalStateException("VIRTUAL_COMPATIBILITY_PROFILE_MISSING");
        if (!VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.webView().mode())
                && !Boolean.TRUE.equals(installed.get("webViewUpdate"))) {
            throw new IllegalStateException("VIRTUAL_WEBVIEW_UPDATE_PROXY_REQUIRED");
        }
        if ((!VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.googleServices().mode())
                || !VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.oem().mode()))
                && !Boolean.TRUE.equals(installed.get("deviceIdentifiers"))) {
            throw new IllegalStateException("VIRTUAL_DEVICE_IDENTIFIERS_PROXY_REQUIRED");
        }
        if (!VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.googleServices().mode())
                && profile.googleServices().playServicesAvailable()
                && !Boolean.TRUE.equals(installed.get("googleServiceBroker"))) {
            throw new IllegalStateException("VIRTUAL_GOOGLE_SERVICE_BROKER_REQUIRED");
        }
        if (!VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.oem().mode())
                && !profile.oem().availableServices().isEmpty()
                && !Boolean.TRUE.equals(installed.get("oemIdentifiers"))) {
            throw new IllegalStateException("VIRTUAL_OEM_IDENTIFIER_PROXY_REQUIRED");
        }
        if (!VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.detection().mode())
                && profile.detection().sanitizeProcFiles() && !nativePolicyConfigured) {
            throw new IllegalStateException("VIRTUAL_DETECTION_NATIVE_POLICY_REQUIRED");
        }
    }
}
