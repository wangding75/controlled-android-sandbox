package com.warden.controlledsandbox.runtime.guest;
import com.warden.controlledsandbox.contract.*;
import java.util.List;
import java.util.Map;
public final class PolicyServicesProxyReadinessSelfTest {
    public static void main(String[] args) {
        VirtualPolicyServicesProfileSnapshot staticProfile = profile("STATIC", true);
        PolicyServicesProxyReadiness.require(Map.of("devicePolicy", true, "accessibility", true, "autofill", true, "biometric", true, "sensorPrivacy", true, "power", true, "vibrator", true), staticProfile);
        requireFailure(() -> PolicyServicesProxyReadiness.require(Map.of("devicePolicy", true), staticProfile), "ACCESSIBILITY", "missing accessibility proxy blocks");
        requireFailure(() -> PolicyServicesProxyReadiness.require(Map.of("devicePolicy", true, "accessibility", true, "autofill", true, "biometric", true, "sensorPrivacy", true, "power", true), staticProfile), "VIBRATOR", "missing vibrator proxy blocks");
        PolicyServicesProxyReadiness.require(Map.of(), profile("HOST", true));
        PolicyServicesProxyReadiness.require(Map.of("devicePolicy", true, "accessibility", true, "autofill", true, "biometric", true, "sensorPrivacy", true, "power", true), profile("STATIC", false));
        System.out.println("PASS M5-T13 policy-services proxy readiness self-test");
    }
    private static VirtualPolicyServicesProfileSnapshot profile(String mode, boolean vibration) {
        return new VirtualPolicyServicesProfileSnapshot(1L, 0L, new VirtualDevicePolicyProfileSnapshot(mode, false, false, false, false, false, false, 0, 0, 0), new VirtualAccessibilityProfileSnapshot(mode, false, false, false, false, 4, 0L, List.of()), new VirtualAutofillProfileSnapshot(mode, false, "", false, false, 4, 60000L), new VirtualBiometricProfileSnapshot(mode, false, false, 0, 0, false, false, "FAILURE", 2, 0L), new VirtualSensorPrivacyProfileSnapshot(mode, false, false, false, false, 4), new VirtualPowerProfileSnapshot(mode, true, false, false, false, 4, 10000L, vibration, 4, 1000L));
    }
    private static void requireFailure(Runnable action, String expected, String message){
        boolean failed=false;
        try{
            action.run();
        } catch(IllegalStateException error){
            failed=error.getMessage().contains(expected);
        }
        require(failed, message);
    }
    private static void require(boolean value, String message){
        if(!value)throw new AssertionError(message);
    }
}
