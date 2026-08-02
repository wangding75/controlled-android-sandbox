package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.ControlledReleaseIdentity;
import com.warden.controlledsandbox.contract.NativeCompanionIdentity;

public final class NativeCompanionIdentityVerifierSelfTest {
    private NativeCompanionIdentityVerifierSelfTest() { }

    public static void main(String[] args) {
        NativeCompanionIdentityVerifier.requireCompatible(NativeCompanionIdentity.current());
        expect("NATIVE_COMPANION_IDENTITY_MISSING", () ->
                NativeCompanionIdentityVerifier.requireCompatible(null));
        expect("NATIVE_COMPANION_PRODUCT_MISMATCH", () ->
                NativeCompanionIdentityVerifier.requireCompatible(identity(
                        "other-product", ControlledReleaseIdentity.RELEASE_TRAIN,
                        ControlledReleaseIdentity.VERSION_CODE, ControlledReleaseIdentity.VERSION_NAME,
                        ControlledReleaseIdentity.COMPANION_PROTOCOL,
                        ControlledReleaseIdentity.COMPANION_PROTOCOL)));
        expect("NATIVE_COMPANION_PROTOCOL_INCOMPATIBLE", () ->
                NativeCompanionIdentityVerifier.requireCompatible(identity(
                        ControlledReleaseIdentity.PRODUCT, ControlledReleaseIdentity.RELEASE_TRAIN,
                        ControlledReleaseIdentity.VERSION_CODE, ControlledReleaseIdentity.VERSION_NAME,
                        ControlledReleaseIdentity.COMPANION_PROTOCOL + 1,
                        ControlledReleaseIdentity.COMPANION_PROTOCOL + 1)));
        expect("NATIVE_COMPANION_RELEASE_TRAIN_MISMATCH", () ->
                NativeCompanionIdentityVerifier.requireCompatible(identity(
                        ControlledReleaseIdentity.PRODUCT, "other-train",
                        ControlledReleaseIdentity.VERSION_CODE, ControlledReleaseIdentity.VERSION_NAME,
                        ControlledReleaseIdentity.COMPANION_PROTOCOL,
                        ControlledReleaseIdentity.COMPANION_PROTOCOL)));
        expect("NATIVE_COMPANION_VERSION_IDENTITY_MISMATCH", () ->
                NativeCompanionIdentityVerifier.requireCompatible(identity(
                        ControlledReleaseIdentity.PRODUCT, ControlledReleaseIdentity.RELEASE_TRAIN,
                        ControlledReleaseIdentity.VERSION_CODE + 1, "other-version",
                        ControlledReleaseIdentity.COMPANION_PROTOCOL,
                        ControlledReleaseIdentity.COMPANION_PROTOCOL)));
        NativeCompanionIdentityVerifier.requireInstalledPair(
                NativeCompanionIdentity.current(),
                ControlledReleaseIdentity.VERSION_CODE, ControlledReleaseIdentity.VERSION_NAME,
                ControlledReleaseIdentity.VERSION_CODE, ControlledReleaseIdentity.VERSION_NAME);
        NativeCompanionIdentityVerifier.requireInstalledPair(
                NativeCompanionIdentity.current(),
                ControlledReleaseIdentity.VERSION_CODE, ControlledReleaseIdentity.VERSION_NAME + "-debug",
                ControlledReleaseIdentity.VERSION_CODE, ControlledReleaseIdentity.VERSION_NAME + "-debug");
        expect("NATIVE_COMPANION_INSTALLED_VERSION_CODE_MISMATCH", () ->
                NativeCompanionIdentityVerifier.requireInstalledPair(
                        NativeCompanionIdentity.current(),
                        ControlledReleaseIdentity.VERSION_CODE, ControlledReleaseIdentity.VERSION_NAME,
                        ControlledReleaseIdentity.VERSION_CODE + 1, ControlledReleaseIdentity.VERSION_NAME));
        expect("NATIVE_COMPANION_INSTALLED_VERSION_NAME_MISMATCH", () ->
                NativeCompanionIdentityVerifier.requireInstalledPair(
                        NativeCompanionIdentity.current(),
                        ControlledReleaseIdentity.VERSION_CODE, ControlledReleaseIdentity.VERSION_NAME,
                        ControlledReleaseIdentity.VERSION_CODE, ControlledReleaseIdentity.VERSION_NAME + "-debug"));
        System.out.println("PASS Native Companion release and protocol identity verifier self-test");
    }

    private static NativeCompanionIdentity identity(
            String product, String releaseTrain, int versionCode, String versionName,
            int minimumProtocol, int maximumProtocol) {
        return new NativeCompanionIdentity(product, releaseTrain, versionCode, versionName,
                minimumProtocol, maximumProtocol);
    }

    private static void expect(String code, Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected failure: " + code);
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().startsWith(code)) {
                throw new AssertionError("unexpected failure: " + expected.getMessage(), expected);
            }
        }
    }
}
