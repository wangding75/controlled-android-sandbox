package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCameraSourceSnapshot;
import com.warden.controlledsandbox.contract.VirtualCompanionDeviceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaProjectionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNfcProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualOemSystemServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrintProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsbProfileSnapshot;
import java.util.List;

/** Deterministic fail-closed defaults for peripheral and external system services. */
final class VirtualPeripheralServicesDefaults {
    private VirtualPeripheralServicesDefaults() { }

    static VirtualPeripheralServicesProfileSnapshot create(
            String packageName, int virtualUserId, long version, long updatedAtMs) {
        String mode = VirtualLocationProfileSnapshot.MODE_STATIC;
        VirtualNfcProfileSnapshot nfc = new VirtualNfcProfileSnapshot(
                mode, "OFF", false, false, false, 0, 0, List.of());
        VirtualUsbProfileSnapshot usb = new VirtualUsbProfileSnapshot(
                mode, false, false, false, false, 0, "none", List.of(), List.of());
        VirtualPrintProfileSnapshot printing = new VirtualPrintProfileSnapshot(
                mode, false, false, 0, "", "", List.of());
        VirtualCompanionDeviceProfileSnapshot companion =
                new VirtualCompanionDeviceProfileSnapshot(
                        mode, false, false, false, false, 0, List.of(), List.of());
        VirtualMediaProjectionProfileSnapshot projection =
                new VirtualMediaProjectionProfileSnapshot(
                        mode, false, false, false, true, 0, 1080, 1920, 420);
        VirtualCameraProfileSnapshot camera = new VirtualCameraProfileSnapshot(
                mode, false, false, false, 0, List.of(), List.of(), List.of(),
                VirtualCameraSourceSnapshot.none(), false);
        VirtualOemSystemServicesProfileSnapshot oem =
                new VirtualOemSystemServicesProfileSnapshot(
                        mode, List.of(), List.of("get", "is", "has", "query", "check"),
                        List.of("set", "put", "delete", "remove", "enable", "disable",
                                "start", "stop", "register", "unregister"), 0);
        return new VirtualPeripheralServicesProfileSnapshot(
                version, updatedAtMs, nfc, usb, printing, companion, projection, camera, oem);
    }
}
