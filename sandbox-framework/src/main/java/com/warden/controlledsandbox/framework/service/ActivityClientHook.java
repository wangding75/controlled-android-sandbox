package com.warden.controlledsandbox.framework.service;

import android.os.Build;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/**
 * ActivityClient lifecycle bridge. Android 12/API32 and Android 15/API35 both expose the
 * ActivityClientController through INTERFACE_SINGLETON, but newer releases add the
 * mKnownInstance fast cache. The resolver is SDK-based and fails closed for unsupported APIs.
 */
public final class ActivityClientHook {
    static final String CONTROLLER_DESCRIPTOR = "android.app.IActivityClientController";

    private ActivityClientHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk < 29) {
            throw new IllegalStateException("ACTIVITY_CLIENT_UNSUPPORTED_API:" + sdk);
        }
        String structure = sdk >= 35 ? "api35" : "api32-compatible";
        android.util.Log.i("CS_INTERACTION_PROXY", "ACTIVITY_CLIENT_STRUCTURE=" + structure);
        AutoCloseable hook = ReflectiveServiceHook.singletonWithCacheCandidates(
                "android.app.ActivityClient", "INTERFACE_SINGLETON", identity,
                "activityClient", CONTROLLER_DESCRIPTOR, "mKnownInstance");
        android.util.Log.i("CS_INTERACTION_PROXY",
                "ACTIVITY_CLIENT_READY descriptor=" + CONTROLLER_DESCRIPTOR
                        + " cache=mInstance,mKnownInstance");
        return hook;
    }
}
