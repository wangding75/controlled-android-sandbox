package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Bounded AudioManager Binder hook. Native AudioRecord/MediaRecorder paths remain outside this hook. */
public final class AudioCaptureServiceHook {
    private AudioCaptureServiceHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "audio", "audio", identity,
                "mService", "sService");
    }
}
