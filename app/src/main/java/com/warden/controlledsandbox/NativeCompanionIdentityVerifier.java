package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.ControlledReleaseIdentity;
import com.warden.controlledsandbox.contract.NativeCompanionIdentity;

/** Fail-closed compatibility check for an independently installed Companion APK. */
final class NativeCompanionIdentityVerifier {
    private NativeCompanionIdentityVerifier() { }

    static void requireCompatible(NativeCompanionIdentity identity) {
        if (identity == null) {
            throw new IllegalStateException("NATIVE_COMPANION_IDENTITY_MISSING");
        }
        if (!ControlledReleaseIdentity.PRODUCT.equals(identity.product())) {
            throw new IllegalStateException("NATIVE_COMPANION_PRODUCT_MISMATCH");
        }
        if (!identity.supportsProtocol(ControlledReleaseIdentity.COMPANION_PROTOCOL)) {
            throw new IllegalStateException("NATIVE_COMPANION_PROTOCOL_INCOMPATIBLE:host="
                    + ControlledReleaseIdentity.COMPANION_PROTOCOL + ":companion="
                    + identity.minimumProtocol() + "-" + identity.maximumProtocol());
        }
        if (!ControlledReleaseIdentity.RELEASE_TRAIN.equals(identity.releaseTrain())) {
            throw new IllegalStateException("NATIVE_COMPANION_RELEASE_TRAIN_MISMATCH:host="
                    + ControlledReleaseIdentity.RELEASE_TRAIN + ":companion=" + identity.releaseTrain());
        }
        if (identity.versionCode() != ControlledReleaseIdentity.VERSION_CODE
                || !ControlledReleaseIdentity.VERSION_NAME.equals(identity.versionName())) {
            throw new IllegalStateException("NATIVE_COMPANION_VERSION_IDENTITY_MISMATCH:host="
                    + ControlledReleaseIdentity.VERSION_CODE + "/" + ControlledReleaseIdentity.VERSION_NAME
                    + ":companion=" + identity.versionCode() + "/" + identity.versionName());
        }
    }

    static void requireInstalledPair(
            NativeCompanionIdentity identity,
            long hostVersionCode,
            String hostVersionName,
            long companionVersionCode,
            String companionVersionName) {
        requireCompatible(identity);
        String hostName = normalized(hostVersionName);
        String companionName = normalized(companionVersionName);
        if (hostVersionCode != identity.versionCode()
                || companionVersionCode != identity.versionCode()
                || hostVersionCode != companionVersionCode) {
            throw new IllegalStateException("NATIVE_COMPANION_INSTALLED_VERSION_CODE_MISMATCH:host="
                    + hostVersionCode + ":companion=" + companionVersionCode
                    + ":identity=" + identity.versionCode());
        }
        if (!hostName.equals(companionName)) {
            throw new IllegalStateException("NATIVE_COMPANION_INSTALLED_VERSION_NAME_MISMATCH:host="
                    + hostName + ":companion=" + companionName);
        }
        if (!hostName.equals(identity.versionName())
                && !hostName.equals(identity.versionName() + "-debug")) {
            throw new IllegalStateException("NATIVE_COMPANION_INSTALLED_RELEASE_IDENTITY_MISMATCH:"
                    + hostName + ":identity=" + identity.versionName());
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
