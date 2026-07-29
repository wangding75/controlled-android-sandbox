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

    public static void requireRuntimePeer(Context context) {
        RuntimePeerPolicy.requireTrustedBinderCaller(context);
    }
}
