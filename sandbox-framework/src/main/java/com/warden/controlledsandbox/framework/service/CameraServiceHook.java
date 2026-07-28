package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Best-effort reversible camera Binder hook; method decisions remain fail-closed in the proxy. */
public final class CameraServiceHook {
    private CameraServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "camera", "camera", identity,
                "mService", "mCameraService", "mGlobal.mCameraService", "mCameraManagerGlobal.mCameraService");
    }
}
