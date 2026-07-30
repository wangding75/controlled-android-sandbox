package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Durable M5-T15 peripheral-service profile isolation, versioning and corruption tests. */
public final class VirtualPeripheralServicesStoreSelfTest {
    public static void main(String[] args) throws Exception {
        File root = new File("build/peripheral-services-store-self-test").getCanonicalFile();
        delete(root);
        root.mkdirs();
        VirtualPeripheralServicesStore store = new VirtualPeripheralServicesStore(root);
        VirtualSystemServiceStore.Scope user0 = new VirtualSystemServiceStore.Scope("guest.peripheral", 0);
        VirtualSystemServiceStore.Scope user1 = new VirtualSystemServiceStore.Scope("guest.peripheral", 1);
        VirtualPeripheralServicesProfileSnapshot first = store.getOrCreate(user0);
        VirtualPeripheralServicesProfileSnapshot other = store.getOrCreate(user1);
        require(first != other && first.policyVersion() == 1L && other.policyVersion() == 1L,
                "per-user peripheral scope isolation");
        require(!first.nfc().readerModeAllowed() && !first.usb().allowOpenDevice()
                        && !first.printing().allowPrintJobs()
                        && !first.mediaProjection().allowScreenCapture()
                        && !first.camera().allowOpen(),
                "defaults fail closed");

        VirtualPeripheralServicesProfileSnapshot requested = new VirtualPeripheralServicesProfileSnapshot(
                first.policyVersion(), first.updatedAtMs(),
                new VirtualNfcProfileSnapshot("STATIC", "ON", true, true, false,
                        1, 2, List.of("tag-1")),
                new VirtualUsbProfileSnapshot("STATIC", true, true, true, true,
                        1, "mtp", List.of("usb-1"), List.of("accessory-1")),
                new VirtualPrintProfileSnapshot("STATIC", true, true, 1,
                        "printer-1", "Office", List.of("print.service")),
                new VirtualCompanionDeviceProfileSnapshot("STATIC", true, true, true,
                        false, 2, List.of("association-1"), List.of("watch")),
                new VirtualMediaProjectionProfileSnapshot("STATIC", true, true, false,
                        false, 1, 1080, 1920, 420),
                new VirtualCameraProfileSnapshot("STATIC", true, true, true, 1,
                        List.of("0", "1"), List.of("1"), List.of("0")),
                new VirtualOemSystemServicesProfileSnapshot("STATIC", List.of("vendor.demo"),
                        List.of("get", "is"), List.of("set", "delete"), 1));
        VirtualPeripheralServicesProfileSnapshot updated = store.update(user0, requested);
        require(updated.policyVersion() == 2L && updated.nfc().readerModeAllowed()
                        && updated.usb().approvedDeviceNames().contains("usb-1")
                        && updated.camera().cameraIds().size() == 2,
                "optimistic peripheral update");
        boolean conflict = false;
        try {
            store.update(user0, requested);
        } catch (IllegalStateException expected) {
            conflict = expected.getMessage().contains("VERSION_CONFLICT");
        }
        require(conflict, "stale peripheral update rejected");

        VirtualPeripheralServicesStore reloaded = new VirtualPeripheralServicesStore(root);
        VirtualPeripheralServicesProfileSnapshot persisted = reloaded.getOrCreate(user0);
        require("mtp".equals(persisted.usb().defaultFunctions())
                        && persisted.mediaProjection().allowScreenCapture()
                        && persisted.oemSystemServices().serviceNames().contains("vendor.demo"),
                "profile persisted");
        require(!reloaded.getOrCreate(user1).usb().allowOpenDevice(),
                "other virtual user remains isolated");

        File file = new File(new File(root, "package-service"),
                "virtual-peripheral-services-v1.json");
        Files.writeString(file.toPath(), "corrupt");
        VirtualPeripheralServicesStore corrupted = new VirtualPeripheralServicesStore(root);
        require(!corrupted.maintenanceWarning().isEmpty()
                        && new File(file.getParentFile(), file.getName() + ".corrupt").isFile(),
                "corrupt peripheral store quarantined");
        System.out.println("PASS M5-T15 peripheral-services profile store self-test");
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
