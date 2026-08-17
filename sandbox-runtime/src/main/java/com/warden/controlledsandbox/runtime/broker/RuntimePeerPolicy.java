package com.warden.controlledsandbox.runtime.broker;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import com.warden.controlledsandbox.contract.RuntimePeerIdentity;

/** Shared package/signature boundary for Host and the independently packaged 32-bit runtime peer. */
public final class RuntimePeerPolicy {
    public static final String SIGNATURE_PERMISSION = RuntimePeerIdentity.SIGNATURE_PERMISSION;
    public static final String HOST_RELEASE_PACKAGE = RuntimePeerIdentity.HOST_RELEASE_PACKAGE;
    public static final String HOST_DEBUG_PACKAGE = RuntimePeerIdentity.HOST_DEBUG_PACKAGE;
    public static final String COMPANION_RELEASE_PACKAGE = RuntimePeerIdentity.COMPANION_RELEASE_PACKAGE;
    public static final String COMPANION_DEBUG_PACKAGE = RuntimePeerIdentity.COMPANION_DEBUG_PACKAGE;

    private RuntimePeerPolicy() { }

    private static volatile RuntimeIsolatedPeerRegistry isolatedPeers;

    public static void installIsolatedPeerRegistry(RuntimeIsolatedPeerRegistry registry) {
        isolatedPeers = registry;
    }

    public static void requireTrustedBinderCaller(Context context) {
        int callerUid = Binder.getCallingUid();
        if (callerUid == Process.myUid()) return;
        if (context != null && context.checkCallingPermission(SIGNATURE_PERMISSION)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        RuntimeIsolatedPeerRegistry registry = isolatedPeers;
        if (registry != null && registry.isRegisteredIsolatedPeer(callerUid)) return;
        throw new SecurityException("UNTRUSTED_RUNTIME_PEER_UID:" + callerUid);
    }

    public static String hostPackageFor(Context context) {
        String current = context == null ? "" : context.getPackageName();
        if (COMPANION_DEBUG_PACKAGE.equals(current)) return HOST_DEBUG_PACKAGE;
        if (COMPANION_RELEASE_PACKAGE.equals(current)) return HOST_RELEASE_PACKAGE;
        return current;
    }

    public static boolean isCompanionPackage(String packageName) {
        return RuntimePeerIdentity.isCompanionPackage(packageName);
    }

    public static String companionBrokerProcess(String companionPackage) {
        return RuntimePeerIdentity.companionBrokerProcess(companionPackage);
    }
}
