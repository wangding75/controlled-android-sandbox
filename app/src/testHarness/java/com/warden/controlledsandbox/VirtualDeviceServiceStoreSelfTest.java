package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Host-side persistence, isolation and optimistic-version tests for M5-T8 device profiles. */
public final class VirtualDeviceServiceStoreSelfTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("device-service-store").toFile();
        testDeterministicIsolationAndPersistence(root);
        testVersionConflictAndReset(root);
        testCorruptionQuarantine();
        delete(root);
        System.out.println("PASS virtual device-service profile store self-test");
    }

    private static void testDeterministicIsolationAndPersistence(File root) {
        VirtualDeviceServiceStore store = new VirtualDeviceServiceStore(root);
        VirtualSystemServiceStore.Scope user0 = new VirtualSystemServiceStore.Scope("profile.pkg", 0);
        VirtualSystemServiceStore.Scope user1 = new VirtualSystemServiceStore.Scope("profile.pkg", 1);
        VirtualDeviceServiceProfileSnapshot first = store.getOrCreate(user0);
        VirtualDeviceServiceProfileSnapshot repeated = store.getOrCreate(user0);
        VirtualDeviceServiceProfileSnapshot secondUser = store.getOrCreate(user1);
        require(first.identity().androidId().equals(repeated.identity().androidId()),
                "same scope keeps deterministic Android ID");
        require(!first.identity().androidId().equals(secondUser.identity().androidId()),
                "virtual users receive isolated identities");
        require(first.location().mode().equals(VirtualLocationProfileSnapshot.MODE_BLOCKED),
                "location defaults fail closed");
        require(!first.telephony().defaultSlot().imei().isEmpty(), "telephony identity generated");
        int firstOctet = Integer.parseInt(first.wifi().macAddress().substring(0, 2), 16);
        require((firstOctet & 0x02) != 0 && (firstOctet & 0x01) == 0,
                "Wi-Fi MAC is locally administered and unicast");

        VirtualDeviceServiceStore reloaded = new VirtualDeviceServiceStore(root);
        VirtualDeviceServiceProfileSnapshot restored = reloaded.getOrCreate(user0);
        require(first.identity().androidId().equals(restored.identity().androidId()),
                "profile survives store reload");
        require(reloaded.scopeCount() == 2, "all scopes survive reload");
    }

    private static void testVersionConflictAndReset(File root) {
        VirtualDeviceServiceStore store = new VirtualDeviceServiceStore(root);
        VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope("profile.pkg", 0);
        VirtualDeviceServiceProfileSnapshot current = store.getOrCreate(scope);
        VirtualLocationProfileSnapshot staticLocation = new VirtualLocationProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, "gps", true,
                31.2304d, 121.4737d, 12d, 8f, 0f, 0f,
                1234L, 5678L, 1000L, true, 12, 8,
                "$GPGGA,000000.00,3113.824,N,12128.422,E,1,08,1.0,12.0,M,0.0,M,,*");
        VirtualDeviceServiceProfileSnapshot requested = new VirtualDeviceServiceProfileSnapshot(
                current.policyVersion(), current.updatedAtMs(), staticLocation, current.identity(),
                current.telephony(), current.wifi(), current.bluetooth(), current.sensors());
        VirtualDeviceServiceProfileSnapshot updated = store.update(scope, requested);
        require(updated.policyVersion() == current.policyVersion() + 1L,
                "successful update advances policy version");
        require(updated.location().latitude() == 31.2304d, "static location persisted");
        boolean conflict = false;
        try { store.update(scope, requested); }
        catch (IllegalStateException expected) {
            conflict = expected.getMessage().startsWith("DEVICE_PROFILE_VERSION_CONFLICT");
        }
        require(conflict, "stale profile update rejected");
        VirtualDeviceServiceProfileSnapshot reset = store.reset(scope);
        require(reset.policyVersion() == updated.policyVersion() + 1L,
                "reset advances version");
        require(reset.location().mode().equals(VirtualLocationProfileSnapshot.MODE_BLOCKED),
                "reset restores fail-closed location");
    }

    private static void testCorruptionQuarantine() throws Exception {
        File root = Files.createTempDirectory("device-service-corrupt").toFile();
        File storeFile = new File(new File(root, "package-service"),
                "virtual-device-services-v1.json");
        require(storeFile.getParentFile().mkdirs(), "corrupt store parent created");
        Files.writeString(storeFile.toPath(), "not-json", StandardCharsets.UTF_8);
        VirtualDeviceServiceStore store = new VirtualDeviceServiceStore(root);
        require(!store.maintenanceWarning().isEmpty(), "corruption is surfaced as maintenance warning");
        require(new File(storeFile.getParentFile(), storeFile.getName() + ".corrupt").isFile(),
                "corrupt file quarantined");
        delete(root);
    }

    private static void delete(File value) {
        if (value == null || !value.exists()) return;
        if (value.isDirectory()) {
            File[] children = value.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        if (!value.delete()) value.deleteOnExit();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
