package com.warden.controlledsandbox.runtime.broker;

import android.content.Context;
import android.os.Binder;
import android.os.Process;

public final class CallerGuard {
    private CallerGuard() { }

    public static void requireSameApplication() {
        int caller = Binder.getCallingUid();
        if (caller != Process.myUid()) {
            throw new SecurityException("Cross-application Binder caller rejected: " + caller);
        }
    }

    /**
     * Isolated workers run under a platform-assigned UID, so same-UID checks are invalid there.
     * The caller must instead be the owning application UID; exported=false remains the manifest
     * boundary and the per-session capability token is validated by the worker itself.
     */
    public static void requireOwningApplication(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        int caller = Binder.getCallingUid();
        int owner = context.getApplicationInfo().uid;
        if (caller != owner) {
            throw new SecurityException("Isolated worker caller is not the owning application: " + caller);
        }
    }

    public static void requireRuntimePeer(Context context) {
        RuntimePeerPolicy.requireTrustedBinderCaller(context);
    }
}
