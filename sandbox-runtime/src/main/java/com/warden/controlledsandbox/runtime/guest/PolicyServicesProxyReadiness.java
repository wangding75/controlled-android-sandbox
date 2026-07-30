package com.warden.controlledsandbox.runtime.guest;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPolicyServicesProfileSnapshot;
import java.util.Map;
/** Fail-closed readiness gate for policy/accessibility/autofill/biometric/privacy/power proxies. */ final class PolicyServicesProxyReadiness {
    private PolicyServicesProxyReadiness() {
    }
    static void require(Map<String, Boolean> installed, VirtualPolicyServicesProfileSnapshot profile) {
        if (profile == null) throw new IllegalStateException("VIRTUAL_POLICY_SERVICES_PROFILE_MISSING");
        require(installed, "devicePolicy", profile.devicePolicy().mode(), "VIRTUAL_DEVICE_POLICY_PROXY_REQUIRED");
        require(installed, "accessibility", profile.accessibility().mode(), "VIRTUAL_ACCESSIBILITY_PROXY_REQUIRED");
        require(installed, "autofill", profile.autofill().mode(), "VIRTUAL_AUTOFILL_PROXY_REQUIRED");
        require(installed, "biometric", profile.biometric().mode(), "VIRTUAL_BIOMETRIC_PROXY_REQUIRED");
        require(installed, "sensorPrivacy", profile.sensorPrivacy().mode(), "VIRTUAL_SENSOR_PRIVACY_PROXY_REQUIRED");
        require(installed, "power", profile.power().mode(), "VIRTUAL_POWER_PROXY_REQUIRED");
        if (!VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.power().mode()) && profile.power().allowVibration() && !Boolean.TRUE.equals(installed.get("vibrator"))) {
            throw new IllegalStateException("VIRTUAL_VIBRATOR_PROXY_REQUIRED");
        }
    }
    private static void require(Map<String, Boolean> installed, String key, String mode, String error) {
        if (!VirtualLocationProfileSnapshot.MODE_HOST.equals(mode) && !Boolean.TRUE.equals(installed.get(key))) throw new IllegalStateException(error);
    }
}
