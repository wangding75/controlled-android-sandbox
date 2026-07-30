package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Durable M5-T14 media/communication profile isolation, versioning and corruption tests. */
public final class VirtualMediaCommunicationStoreSelfTest {
    public static void main(String[] args) throws Exception {
        File root = new File("build/media-communication-store-self-test").getCanonicalFile();
        delete(root);
        root.mkdirs();
        VirtualMediaCommunicationStore store = new VirtualMediaCommunicationStore(root);
        VirtualSystemServiceStore.Scope user0 = new VirtualSystemServiceStore.Scope("guest.media", 0);
        VirtualSystemServiceStore.Scope user1 = new VirtualSystemServiceStore.Scope("guest.media", 1);
        VirtualMediaCommunicationProfileSnapshot first = store.getOrCreate(user0);
        VirtualMediaCommunicationProfileSnapshot other = store.getOrCreate(user1);
        require(first != other && first.policyVersion() == 1L && other.policyVersion() == 1L,
                "per-user media scope isolation");
        require(!first.messaging().allowTextMessages() && !first.backup().allowBackupNow()
                && !first.dropBox().allowWrites(), "defaults fail closed");
        VirtualMediaCommunicationProfileSnapshot requested = new VirtualMediaCommunicationProfileSnapshot(
                first.policyVersion(), first.updatedAtMs(),
                new VirtualMediaSessionProfileSnapshot("STATIC", true, true, true, 2,
                        "PLAYING", 42L, "Track", "Artist"),
                new VirtualMediaRouterProfileSnapshot("STATIC", "speaker", "Speaker", 2,
                        7, 15, true, 2),
                new VirtualAudioRoutingProfileSnapshot("STATIC", 3, 2, true, false, false,
                        7, 15, true, true, 2),
                new VirtualMessagingProfileSnapshot("STATIC", 1, "guest.media", true,
                        true, true, 2, 60000L, true),
                new VirtualBackupProfileSnapshot("STATIC", true, true, "local",
                        List.of("local"), true, true, false),
                new VirtualDropBoxProfileSnapshot("STATIC", List.of("crash"), true,
                        false, 8, 4096));
        VirtualMediaCommunicationProfileSnapshot updated = store.update(user0, requested);
        require(updated.policyVersion() == 2L && updated.mediaSession().active()
                && updated.messaging().allowTextMessages(), "optimistic media update");
        boolean conflict = false;
        try { store.update(user0, requested); }
        catch (IllegalStateException expected) { conflict = expected.getMessage().contains("VERSION_CONFLICT"); }
        require(conflict, "stale media update rejected");
        VirtualMediaCommunicationStore reloaded = new VirtualMediaCommunicationStore(root);
        require(reloaded.getOrCreate(user0).audioRouting().speakerphoneOn(), "profile persisted");
        File file = new File(new File(root, "package-service"), "virtual-media-communication-v1.json");
        Files.writeString(file.toPath(), "corrupt");
        VirtualMediaCommunicationStore corrupted = new VirtualMediaCommunicationStore(root);
        require(!corrupted.maintenanceWarning().isEmpty()
                && new File(file.getParentFile(), file.getName() + ".corrupt").isFile(),
                "corrupt media store quarantined");
        System.out.println("PASS M5-T14 media-communication profile store self-test");
    }

    private static void delete(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) for (File child : file.listFiles()) delete(child);
        file.delete();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
