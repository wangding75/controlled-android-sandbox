package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.List;

/** Regression checks for the Guest Prepare tier audit and its explicit lazy boundaries. */
public final class GuestPreparePlanSelfTest {
    public static void main(String[] args) {
        GuestPreparePlan regular = GuestPreparePlan.forFlags(false, true);
        require(regular.names(GuestPreparePlan.Tier.LAUNCH_CRITICAL).contains("nativeBootstrap"),
                "native bootstrap remains launch critical");
        require(regular.names(GuestPreparePlan.Tier.LAUNCH_CRITICAL).contains("applicationOnCreate"),
                "Application.onCreate remains launch critical");
        require(regular.names(GuestPreparePlan.Tier.LAUNCH_CRITICAL).contains("clipboard"),
                "clipboard remains launch-critical while it is a mandatory framework gate");
        require(regular.names(GuestPreparePlan.Tier.FIRST_USE).contains("telephony"),
                "telephony is audited as first-use proxy");
        require(regular.names(GuestPreparePlan.Tier.FIRST_USE).contains("sensorCatalog"),
                "sensor is audited as first-use proxy");
        require(regular.names(GuestPreparePlan.Tier.BACKGROUND).contains("content"),
                "content hooks are audited as background candidate");
        require(regular.names(GuestPreparePlan.Tier.FIRST_USE).contains("webViewUpdate"),
                "WebView provider boundary stays first-use until proven deferrable");
        require(regular.deferredNames().equals(List.of("camera1Adapter", "webViewStorageDirectories")),
                "only independently guarded operations are deferred");
        require(GuestPreparePlan.hookTier("googleServiceBroker")
                        == GuestPreparePlan.Tier.FIRST_USE,
                "GMS proxy is not silently made lazy");
        require(GuestPreparePlan.isDeferred("camera1Adapter"), "Camera1 lazy boundary");
        require(!GuestPreparePlan.isDeferred("googleServiceBroker"),
                "GMS proxy requires explicit first-use proof");
        require(!GuestPreparePlan.forFlags(true, true).deferredNames().contains("camera1Adapter"),
                "isolated path has no host Camera1 patch boundary");
        android.os.Bundle bundle = regular.toBundle();
        require(bundle.getInt(RuntimeKeys.GUEST_PREPARE_PLAN_VERSION, 0)
                        == GuestPreparePlan.VERSION,
                "plan version emitted");
        require("EXPLICIT_BOUNDARIES_ONLY".equals(
                        bundle.getString(RuntimeKeys.GUEST_PREPARE_LAZY_POLICY, "")),
                "lazy policy is explicit");
        System.out.println("PASS GuestPreparePlanSelfTest");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private GuestPreparePlanSelfTest() { }
}
