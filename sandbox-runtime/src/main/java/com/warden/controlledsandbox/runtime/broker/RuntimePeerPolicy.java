package com.warden.controlledsandbox.runtime.broker;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;

/** Shared package/signature boundary for Host and the independently packaged 32-bit runtime peer. */
public final class RuntimePeerPolicy {
    public static final String SIGNATURE_PERMISSION =
            "com.warden.controlledsandbox.permission.BIND_NATIVE_COMPANION";
    public static final String HOST_RELEASE_PACKAGE = "com.warden.controlledsandbox";
    public static final String HOST_DEBUG_PACKAGE = HOST_RELEASE_PACKAGE + ".debug";
    public static final String COMPANION_RELEASE_PACKAGE = HOST_RELEASE_PACKAGE + ".companion32";
    public static final String COMPANION_DEBUG_PACKAGE = COMPANION_RELEASE_PACKAGE + ".debug";

    private RuntimePeerPolicy() { }

    public static void requireTrustedBinderCaller(Context context) {
        int callerUid = Binder.getCallingUid();
        if (callerUid == Process.myUid()) return;
        if (context == null || context.checkCallingPermission(SIGNATURE_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("UNTRUSTED_RUNTIME_PEER_UID:" + callerUid);
        }
    }

    public static String hostPackageFor(Context context) {
        String current = context == null ? "" : context.getPackageName();
        if (COMPANION_DEBUG_PACKAGE.equals(current)) return HOST_DEBUG_PACKAGE;
        if (COMPANION_RELEASE_PACKAGE.equals(current)) return HOST_RELEASE_PACKAGE;
        return current;
    }

    public static boolean isCompanionPackage(String packageName) {
        return COMPANION_RELEASE_PACKAGE.equals(packageName)
                || COMPANION_DEBUG_PACKAGE.equals(packageName);
    }

    public static String companionBrokerProcess(String companionPackage) {
        if (!isCompanionPackage(companionPackage)) {
            throw new IllegalArgumentException("companion package is invalid");
        }
        return companionPackage + ":sandbox_server32";
    }
}
