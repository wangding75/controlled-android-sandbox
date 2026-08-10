package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/**
 * Descriptor-validated AudioManager boundary paired with the generation-bound native audio
 * capture gate. Android API32/API35 resolve AudioManager through the static sService cache and
 * the {@code audio} ServiceManager entry; neither release uses the old instance mService field.
 */
public final class AudioCaptureServiceHook {
    static final String AUDIO_SERVICE = "audio";
    static final String AUDIO_DESCRIPTOR = "android.media.IAudioService";

    private AudioCaptureServiceHook() { }

    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        AutoCloseable binding = ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                context, AUDIO_SERVICE, AUDIO_SERVICE, AUDIO_DESCRIPTOR, identity,
                java.util.List.of(AUDIO_SERVICE), "sService");
        android.util.Log.i("CS_AUDIO", "AUDIO_ROUTING_PROXY_READY service=" + AUDIO_SERVICE
                + " descriptor=" + AUDIO_DESCRIPTOR
                + " manager=AudioManager.sService-or-lazy-ServiceManager cache=synchronized");
        return binding;
    }
}
