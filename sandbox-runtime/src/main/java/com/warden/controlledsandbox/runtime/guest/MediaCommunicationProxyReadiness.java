package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaCommunicationProfileSnapshot;
import java.util.Map;

/** Fail-closed readiness for media, routing, messaging, backup and DropBox proxies. */
final class MediaCommunicationProxyReadiness {
    private MediaCommunicationProxyReadiness() { }

    static void require(Map<String, Boolean> installed, VirtualMediaCommunicationProfileSnapshot profile) {
        if (profile == null) throw new IllegalStateException("VIRTUAL_MEDIA_COMMUNICATION_PROFILE_MISSING");
        require(installed, "mediaSession", profile.mediaSession().mode(), "VIRTUAL_MEDIA_SESSION_PROXY_REQUIRED");
        require(installed, "mediaRouter", profile.mediaRouter().mode(), "VIRTUAL_MEDIA_ROUTER_PROXY_REQUIRED");
        require(installed, "audioCapture", profile.audioRouting().mode(), "VIRTUAL_AUDIO_ROUTING_PROXY_REQUIRED");
        require(installed, "sms", profile.messaging().mode(), "VIRTUAL_SMS_PROXY_REQUIRED");
        require(installed, "backup", profile.backup().mode(), "VIRTUAL_BACKUP_PROXY_REQUIRED");
        require(installed, "dropBox", profile.dropBox().mode(), "VIRTUAL_DROPBOX_PROXY_REQUIRED");
    }

    private static void require(Map<String, Boolean> installed, String key, String mode, String error) {
        if (!VirtualLocationProfileSnapshot.MODE_HOST.equals(mode)
                && !Boolean.TRUE.equals(installed.get(key))) {
            throw new IllegalStateException(error);
        }
    }
}
