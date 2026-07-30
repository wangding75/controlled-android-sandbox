package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualConnectivityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkSnapshot;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Persistence, isolation and optimistic-version tests for M5-T10 network profiles. */
public final class VirtualNetworkServiceStoreSelfTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("network-profile-store").toFile();
        VirtualSystemServiceStore.Scope user0 = new VirtualSystemServiceStore.Scope("guest.pkg", 0);
        VirtualSystemServiceStore.Scope user1 = new VirtualSystemServiceStore.Scope("guest.pkg", 1);
        VirtualNetworkServiceStore store = new VirtualNetworkServiceStore(root);
        VirtualNetworkServiceProfileSnapshot first = store.getOrCreate(user0);
        VirtualNetworkServiceProfileSnapshot other = store.getOrCreate(user1);
        require(first.connectivity().defaultNetworkId() != other.connectivity().defaultNetworkId(),
                "virtual users receive isolated network defaults");
        VirtualNetworkSnapshot offline = new VirtualNetworkSnapshot(77, VirtualNetworkSnapshot.ETHERNET,
                false, false, true, false, false, "eth0", 1500, 0, 0,
                List.of("198.51.100.10/24"), List.of("198.51.100.53"), List.of(), "");
        VirtualNetworkServiceProfileSnapshot requested = new VirtualNetworkServiceProfileSnapshot(
                first.policyVersion(), first.updatedAtMs(),
                new VirtualConnectivityProfileSnapshot(first.connectivity().mode(), 77,
                        false, true, 2, List.of(offline)), first.dns(), first.proxy(), first.vpn());
        VirtualNetworkServiceProfileSnapshot updated = store.update(user0, requested);
        require(updated.policyVersion() == first.policyVersion() + 1L
                        && updated.connectivity().backgroundRestricted(),
                "network update increments version and persists typed policy");
        boolean conflict = false;
        try { store.update(user0, requested); }
        catch (IllegalStateException expected) {
            conflict = expected.getMessage().contains("NETWORK_PROFILE_VERSION_CONFLICT");
        }
        require(conflict, "stale network profile rejected with VERSION_CONFLICT");
        boolean hostDnsRejected = false;
        try { new com.warden.controlledsandbox.contract.VirtualDnsProfileSnapshot(
                com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot.MODE_STATIC,
                List.of("resolver.example"), List.of(),
                com.warden.controlledsandbox.contract.VirtualDnsProfileSnapshot.PRIVATE_DNS_OFF,
                "", false, List.of()); }
        catch (IllegalArgumentException expected) { hostDnsRejected = expected.getMessage().contains("IP literals"); }
        require(hostDnsRejected, "DNS server hostnames are rejected before framework projection");
        VirtualNetworkServiceStore reloaded = new VirtualNetworkServiceStore(root);
        require(reloaded.getOrCreate(user0).connectivity().backgroundRestricted(),
                "network profile survives Package Service restart");
        File file = new File(new File(root, "package-service"), "virtual-network-services-v1.json");
        Files.writeString(file.toPath(), "{broken", StandardCharsets.UTF_8);
        VirtualNetworkServiceStore corrupt = new VirtualNetworkServiceStore(root);
        require(!corrupt.maintenanceWarning().isEmpty(), "corrupt network file quarantined");
        require(new File(file.getParentFile(), file.getName() + ".corrupt").isFile(),
                "corrupt network profile moved aside");
        delete(root);
        System.out.println("PASS M5-T10 network profile store self-test");
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
