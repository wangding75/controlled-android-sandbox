package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualAudioRoutingProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualBackupProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDropBoxProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaCommunicationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaRouterProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaSessionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMessagingProfileSnapshot;
import java.util.List;

/** Deterministic fail-closed defaults for media, communication and archival services. */
final class VirtualMediaCommunicationDefaults {
    private VirtualMediaCommunicationDefaults() { }

    static VirtualMediaCommunicationProfileSnapshot create(
            String packageName, int virtualUserId, long version, long updatedAtMs) {
        String mode = VirtualLocationProfileSnapshot.MODE_STATIC;
        VirtualMediaSessionProfileSnapshot sessions = new VirtualMediaSessionProfileSnapshot(
                mode, false, true, false, 8, "STOPPED", 0L, "", "");
        VirtualMediaRouterProfileSnapshot router = new VirtualMediaRouterProfileSnapshot(
                mode, "local", "This device", 1, 5, 15, false, 8);
        VirtualAudioRoutingProfileSnapshot audio = new VirtualAudioRoutingProfileSnapshot(
                mode, 0, 2, false, false, false, 5, 15, true, true, 8);
        VirtualMessagingProfileSnapshot messaging = new VirtualMessagingProfileSnapshot(
                mode, -1, "", false, false, false, 0, 60000L, false);
        VirtualBackupProfileSnapshot backup = new VirtualBackupProfileSnapshot(
                mode, false, false, "", List.of(), true, false, false);
        VirtualDropBoxProfileSnapshot dropBox = new VirtualDropBoxProfileSnapshot(
                mode, List.of(), false, false, 0, 0);
        return new VirtualMediaCommunicationProfileSnapshot(
                version, updatedAtMs, sessions, router, audio, messaging, backup, dropBox);
    }
}
