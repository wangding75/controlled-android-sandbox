package com.warden.controlledsandbox;

/** Regression tests for permission/AppOps merge rules independent of Android package parsing. */
public final class VirtualPackageStateBuilderPolicySelfTest {
    private VirtualPackageStateBuilderPolicySelfTest() { }

    public static void main(String[] args) {
        require(SandboxPolicyState.APP_OP_IGNORED.equals(
                        VirtualPackageStateBuilder.effectiveAppOpMode(false,
                                SandboxPolicyState.APP_OP_ALLOWED)),
                "AppOps ALLOWED must not bypass a denied effective permission");
        require(SandboxPolicyState.APP_OP_ERRORED.equals(
                        VirtualPackageStateBuilder.effectiveAppOpMode(false,
                                SandboxPolicyState.APP_OP_ERRORED)),
                "stricter AppOps denial must be preserved");
        require(SandboxPolicyState.APP_OP_ALLOWED.equals(
                        VirtualPackageStateBuilder.effectiveAppOpMode(true,
                                SandboxPolicyState.APP_OP_DEFAULT)),
                "effective grant must synthesize ALLOWED when no override exists");
        require(SandboxPolicyState.APP_OP_IGNORED.equals(
                        VirtualPackageStateBuilder.effectiveAppOpMode(true,
                                SandboxPolicyState.APP_OP_IGNORED)),
                "explicit virtual AppOps denial must remain effective after permission grant");
        System.out.println("PASS virtual permission/AppOps merge policy self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
