package com.warden.controlledsandbox.sdk;

import java.util.Set;

public final class SandboxSdkSelfTest {
    public static void main(String[] args) {
        SandboxIdentity first = SandboxIdentity.forInstance("com.example.app", 0,
                "com.example.app", 1, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        SandboxIdentity second = SandboxIdentity.forInstance("com.example.app", 1,
                "com.example.app", 1, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        require(!first.storageNamespace().equals(second.storageNamespace()), "instances share storage namespace");
        require(!first.androidIdentityProfile().equals(second.androidIdentityProfile()), "instances share identity profile");

        CompatibilityPatchRegistry registry = new CompatibilityPatchRegistry();
        registry.register(new CompatibilityPatch() {
            @Override public String id() { return "DINGTALK_PRIVATE_V7"; }
            @Override public boolean matches(CompatibilityContext context) {
                return "com.alibaba.android.rimet".equals(context.packageName())
                        && context.versionCode() >= 1178 && context.versionCode() <= 1178
                        && context.hasCapability("framework-routing");
            }
            @Override public String reason() { return "DingTalk private behavior requires evidence-backed gate"; }
            @Override public String whyNotGeneral() { return "Only the target app/version and capability set match"; }
        });
        CompatibilityContext context = new CompatibilityContext("com.alibaba.android.rimet", "7.8.10",
                1178, Set.of("framework-routing"));
        require(!registry.decide(context).enabled(), "patch must be disabled by default");
        registry.enable("DINGTALK_PRIVATE_V7");
        require(registry.decide(context).enabled(), "explicitly enabled patch did not match");
        System.out.println("PASS sandbox-sdk identity and compatibility self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
