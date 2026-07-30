package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.*;
import java.util.List;
import java.util.Map;

public final class MediaCommunicationProxyReadinessSelfTest {
    public static void main(String[] args) {
        VirtualMediaCommunicationProfileSnapshot profile = profile("STATIC");
        MediaCommunicationProxyReadiness.require(Map.of(
                "mediaSession", true, "mediaRouter", true, "audioCapture", true,
                "sms", true, "backup", true, "dropBox", true), profile);
        requireFailure(() -> MediaCommunicationProxyReadiness.require(
                Map.of("mediaSession", true), profile), "MEDIA_ROUTER", "missing router blocks");
        MediaCommunicationProxyReadiness.require(Map.of(), profile("HOST"));
        System.out.println("PASS M5-T14 media-communication proxy readiness self-test");
    }

    private static VirtualMediaCommunicationProfileSnapshot profile(String mode) {
        return new VirtualMediaCommunicationProfileSnapshot(1L, 0L,
                new VirtualMediaSessionProfileSnapshot(mode, false, true, false, 2, "STOPPED", 0L, "", ""),
                new VirtualMediaRouterProfileSnapshot(mode, "local", "Local", 1, 5, 15, false, 2),
                new VirtualAudioRoutingProfileSnapshot(mode, 0, 2, false, false, false, 5, 15, true, true, 2),
                new VirtualMessagingProfileSnapshot(mode, -1, "", false, false, false, 0, 60000L, false),
                new VirtualBackupProfileSnapshot(mode, false, false, "", List.of(), true, false, false),
                new VirtualDropBoxProfileSnapshot(mode, List.of(), false, false, 0, 0));
    }

    private static void requireFailure(Runnable action, String expected, String message) {
        boolean failed = false;
        try { action.run(); }
        catch (IllegalStateException error) { failed = error.getMessage().contains(expected); }
        if (!failed) throw new AssertionError(message);
    }
}
