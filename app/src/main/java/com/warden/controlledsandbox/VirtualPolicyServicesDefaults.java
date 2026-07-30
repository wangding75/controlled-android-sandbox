package com.warden.controlledsandbox;
import com.warden.controlledsandbox.contract.*;
import java.util.List;
/** Deterministic fail-closed defaults for policy-facing framework services. */ final class VirtualPolicyServicesDefaults {
    private VirtualPolicyServicesDefaults() {
    }
    static VirtualPolicyServicesProfileSnapshot create(String packageName, int virtualUserId, long version, long updatedAtMs) {
        String mode = VirtualLocationProfileSnapshot.MODE_STATIC;
        VirtualDevicePolicyProfileSnapshot devicePolicy = new VirtualDevicePolicyProfileSnapshot(mode, false, false, false, false, false, false, 0, 0, 0);
        VirtualAccessibilityProfileSnapshot accessibility = new VirtualAccessibilityProfileSnapshot(mode, false, false, false, false, 8, 0L, List.of());
        VirtualAutofillProfileSnapshot autofill = new VirtualAutofillProfileSnapshot(mode, false, "", false, false, 8, 120000L);
        VirtualBiometricProfileSnapshot biometric = new VirtualBiometricProfileSnapshot(mode, false, false, 0, 0, false, false, VirtualBiometricProfileSnapshot.OUTCOME_FAILURE, 4, 0L);
        VirtualSensorPrivacyProfileSnapshot privacy = new VirtualSensorPrivacyProfileSnapshot(mode, false, false, false, false, 16);
        VirtualPowerProfileSnapshot power = new VirtualPowerProfileSnapshot(mode, true, false, false, false, 16, 300000L, true, 32, 5000L);
        return new VirtualPolicyServicesProfileSnapshot(version, updatedAtMs, devicePolicy, accessibility, autofill, biometric, privacy, power);
    }
}
