package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Durable M5-T17 privileged-service profile isolation and corruption tests. */
public final class VirtualPrivilegedServicesStoreSelfTest {
    public static void main(String[] args) throws Exception {
        File root = new File("build/privileged-services-store-self-test").getCanonicalFile();
        delete(root);
        root.mkdirs();
        VirtualPrivilegedServicesStore store = new VirtualPrivilegedServicesStore(root);
        VirtualSystemServiceStore.Scope user0 = new VirtualSystemServiceStore.Scope("guest.privileged", 0);
        VirtualSystemServiceStore.Scope user1 = new VirtualSystemServiceStore.Scope("guest.privileged", 1);
        VirtualPrivilegedServicesProfileSnapshot first = store.getOrCreate(user0);
        VirtualPrivilegedServicesProfileSnapshot other = store.getOrCreate(user1);
        require(first != other && first.policyVersion() == 1L && other.policyVersion() == 1L,
                "per-user privileged scope isolation");
        require(!first.search().globalSearchEnabled()
                        && !first.graphicsStats().allowBufferRequests()
                        && !first.contextHub().contextHubAvailable()
                        && !first.persistentDataBlock().readable()
                        && !first.systemUpdate().allowStatusSubmission(),
                "privileged defaults fail closed");

        VirtualPrivilegedServicesProfileSnapshot requested = profile(first.policyVersion(), first.updatedAtMs());
        VirtualPrivilegedServicesProfileSnapshot updated = store.update(user0, requested);
        require(updated.policyVersion() == 2L
                        && updated.storageStats().totalBytes() == 1_000_000L
                        && updated.contextHub().hubs().size() == 1
                        && updated.persistentDataBlock().data().length == 3,
                "optimistic privileged update");
        boolean conflict = false;
        try {
            store.update(user0, requested);
        } catch (IllegalStateException expected) {
            conflict = expected.getMessage().contains("VERSION_CONFLICT");
        }
        require(conflict, "stale privileged update rejected");

        VirtualPrivilegedServicesStore reloaded = new VirtualPrivilegedServicesStore(root);
        VirtualPrivilegedServicesProfileSnapshot persisted = reloaded.getOrCreate(user0);
        require("guest.search/.GlobalSearch".equals(persisted.search().globalSearchComponent())
                        && "0x1234".equals(persisted.contextHub().hubs().get(0).nanoAppIds().get(0))
                        && "IN_PROGRESS".equals(persisted.systemUpdate().status()),
                "privileged profile persisted");
        require(!reloaded.getOrCreate(user1).contextHub().contextHubAvailable(),
                "other virtual user remains isolated");

        File file = new File(new File(root, "package-service"),
                "virtual-privileged-services-v1.json");
        Files.writeString(file.toPath(), "corrupt");
        VirtualPrivilegedServicesStore corrupted = new VirtualPrivilegedServicesStore(root);
        require(!corrupted.maintenanceWarning().isEmpty()
                        && new File(file.getParentFile(), file.getName() + ".corrupt").isFile(),
                "corrupt privileged store quarantined");
        System.out.println("PASS M5-T17 privileged-services profile store self-test");
    }

    private static VirtualPrivilegedServicesProfileSnapshot profile(long version, long updatedAt) {
        return new VirtualPrivilegedServicesProfileSnapshot(version, updatedAt,
                new VirtualSearchProfileSnapshot("STATIC", true, true,
                        "guest.search/.GlobalSearch", "guest.search/.WebSearch",
                        List.of("guest.search/.SearchActivity"), List.of("guest.search.suggest"), 20),
                new VirtualStorageStatsProfileSnapshot("STATIC", 1_000_000L, 600_000L,
                        100_000L, 10_000L, 20_000L, 3_000L, 1_000L, true, true),
                new VirtualGraphicsStatsProfileSnapshot("STATIC", true, true, 1,
                        100L, 5L, 10L),
                new VirtualContextHubProfileSnapshot("STATIC", true, true, true, false, 1,
                        List.of(new VirtualContextHubSnapshot(
                                7, "Virtual Hub", "Warden", 1024, List.of("0x1234")))),
                new VirtualPersistentDataBlockProfileSnapshot("STATIC", true, true, false,
                        16, new byte[]{1, 2, 3}, false, 1, true),
                new VirtualSystemUpdateProfileSnapshot("STATIC", true, true,
                        "IN_PROGRESS", "Update", "1.2.3", "2026-07-01", 42, 100L));
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
