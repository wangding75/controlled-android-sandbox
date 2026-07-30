package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualDisplayProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInputMethodProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWindowPolicySnapshot;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Persistence, isolation and optimistic-version tests for M5-T9 interaction profiles. */
public final class VirtualInteractionStoreSelfTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("interaction-store").toFile();
        VirtualSystemServiceStore.Scope user0 = new VirtualSystemServiceStore.Scope("guest.pkg", 0);
        VirtualSystemServiceStore.Scope user1 = new VirtualSystemServiceStore.Scope("guest.pkg", 1);
        VirtualInteractionStore store = new VirtualInteractionStore(root);
        VirtualInteractionProfileSnapshot first = store.getOrCreate(user0);
        VirtualInteractionProfileSnapshot other = store.getOrCreate(user1);
        require(first.display().displays().get(0).widthPixels()
                        != other.display().displays().get(0).widthPixels(),
                "virtual users receive isolated display defaults");
        VirtualInputMethodProfileSnapshot input = new VirtualInputMethodProfileSnapshot(
                VirtualWindowPolicySnapshot.MODE_STATIC, "ime.virtual/.Service",
                List.of("ime.virtual/.Service"), true, false, false, true, 4);
        VirtualInteractionProfileSnapshot requested = new VirtualInteractionProfileSnapshot(
                first.policyVersion(), first.updatedAtMs(), first.window(), input,
                new VirtualDisplayProfileSnapshot(first.display().mode(),
                        first.display().defaultDisplayId(), true, 2, first.display().displays()));
        VirtualInteractionProfileSnapshot updated = store.update(user0, requested);
        require(updated.policyVersion() == first.policyVersion() + 1L
                        && updated.inputMethod().allowPicker(),
                "interaction update increments version and persists typed policy");
        boolean conflict = false;
        try { store.update(user0, requested); }
        catch (IllegalStateException expected) {
            conflict = expected.getMessage().contains("INTERACTION_PROFILE_VERSION_CONFLICT");
        }
        require(conflict, "stale interaction profile rejected with VERSION_CONFLICT");
        VirtualInteractionStore reloaded = new VirtualInteractionStore(root);
        require(reloaded.getOrCreate(user0).inputMethod().allowPicker(),
                "interaction profile survives Package Service restart");
        File file = new File(new File(root, "package-service"), "virtual-interactions-v1.json");
        Files.writeString(file.toPath(), "{broken", StandardCharsets.UTF_8);
        VirtualInteractionStore corrupt = new VirtualInteractionStore(root);
        require(!corrupt.maintenanceWarning().isEmpty(), "corrupt file quarantined");
        require(new File(file.getParentFile(), file.getName() + ".corrupt").isFile(),
                "corrupt interaction profile moved aside");
        delete(root);
        System.out.println("PASS M5-T9 interaction profile store self-test");
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        if (!file.delete()) file.deleteOnExit();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
