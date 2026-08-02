package com.warden.controlledsandbox.contract;

/** Stable package and permission identity shared by Host, Runtime and Companion modules. */
public final class RuntimePeerIdentity {
    public static final String SIGNATURE_PERMISSION =
            "com.warden.controlledsandbox.permission.BIND_NATIVE_COMPANION";
    public static final String HOST_RELEASE_PACKAGE = "com.warden.controlledsandbox";
    public static final String HOST_DEBUG_PACKAGE = HOST_RELEASE_PACKAGE + ".debug";
    public static final String COMPANION_RELEASE_PACKAGE = HOST_RELEASE_PACKAGE + ".companion32";
    public static final String COMPANION_DEBUG_PACKAGE = COMPANION_RELEASE_PACKAGE + ".debug";

    private RuntimePeerIdentity() { }

    public static String companionBrokerProcess(String packageName) {
        if (!isCompanionPackage(packageName)) {
            throw new IllegalArgumentException("companion package is invalid");
        }
        return packageName + ":sandbox_server32";
    }

    public static boolean isCompanionPackage(String packageName) {
        return COMPANION_RELEASE_PACKAGE.equals(packageName)
                || COMPANION_DEBUG_PACKAGE.equals(packageName);
    }
}
