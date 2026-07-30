package com.warden.controlledsandbox;
import com.warden.controlledsandbox.contract.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
/** Durable M5-T13 policy-service profile isolation, versioning and corruption tests. */ public final class VirtualPolicyServicesStoreSelfTest {
    public static void main(String[] args) throws Exception {
        File root = new File("build/policy-services-store-self-test").getCanonicalFile();
        delete(root);
        root.mkdirs();
        VirtualPolicyServicesStore store = new VirtualPolicyServicesStore(root);
        VirtualSystemServiceStore.Scope a = new VirtualSystemServiceStore.Scope("guest.one", 0);
        VirtualSystemServiceStore.Scope b = new VirtualSystemServiceStore.Scope("guest.one", 1);
        VirtualPolicyServicesProfileSnapshot first = store.getOrCreate(a);
        VirtualPolicyServicesProfileSnapshot other = store.getOrCreate(b);
        require(first != other && first.policyVersion() == 1L && other.policyVersion() == 1L, "per-user policy scope isolation");
        require(!first.devicePolicy().adminActive() && !first.biometric().allowAuthentication(), "defaults fail closed");
        VirtualPolicyServicesProfileSnapshot requested = new VirtualPolicyServicesProfileSnapshot(first.policyVersion(), first.updatedAtMs(), new VirtualDevicePolicyProfileSnapshot("STATIC", true, true, false, true, true, true, 65536, 8, 5), new VirtualAccessibilityProfileSnapshot("STATIC", true, true, true, true, 4, 5000L, List.of("guest/.Accessibility")), new VirtualAutofillProfileSnapshot("STATIC", true, "guest/.Autofill", true, false, 2, 60000L), new VirtualBiometricProfileSnapshot("STATIC", true, true, 15, 2, false, true, "FAILURE", 2, 100L), new VirtualSensorPrivacyProfileSnapshot("STATIC", false, true, false, false, 4), new VirtualPowerProfileSnapshot("STATIC", false, true, true, false, 2, 10000L, false, 0, 0L));
        VirtualPolicyServicesProfileSnapshot updated = store.update(a, requested);
        require(updated.policyVersion() == 2L && updated.devicePolicy().cameraDisabled(), "optimistic policy update");
        boolean conflict = false;
        try {
            store.update(a, requested);
        } catch (IllegalStateException expected) {
            conflict = expected.getMessage().contains("VERSION_CONFLICT");
        }
        require(conflict, "stale policy update rejected");
        VirtualPolicyServicesStore reloaded = new VirtualPolicyServicesStore(root);
        require(reloaded.getOrCreate(a).accessibility().enabled(), "policy profile persisted");
        File file = new File(new File(root, "package-service"), "virtual-policy-services-v1.json");
        Files.writeString(file.toPath(), "corrupt");
        VirtualPolicyServicesStore corrupted = new VirtualPolicyServicesStore(root);
        require(!corrupted.maintenanceWarning().isEmpty() && new File(file.getParentFile(), file.getName() + ".corrupt").isFile(), "corrupt policy store quarantined");
        System.out.println("PASS M5-T13 policy-services profile store self-test");
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
