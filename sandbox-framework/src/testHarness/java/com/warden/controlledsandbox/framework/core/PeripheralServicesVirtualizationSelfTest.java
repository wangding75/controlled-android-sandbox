package com.warden.controlledsandbox.framework.core;

import android.content.pm.ApplicationInfo;
import com.warden.controlledsandbox.contract.*;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-side NFC/USB/printing/companion/projection/camera/OEM behavior tests. */
public final class PeripheralServicesVirtualizationSelfTest {
    public static void main(String[] args) {
        GuestIdentity identity = identity("STATIC");
        testNfc(identity);
        testUsb(identity);
        testPrinting(identity);
        testCompanion(identity);
        testProjection(identity);
        testCamera(identity);
        testOem(identity);
        testHostPassThrough();
        System.out.println("PASS M5-T15 peripheral-services virtualization self-test");
    }

    private static void testNfc(GuestIdentity identity) {
        NfcApi nfc = proxy(NfcApi.class, new NfcDelegate(), identity, "nfc");
        require(nfc.getState() == 3 && nfc.isEnabled(), "NFC adapter state projected");
        Object first = new Object();
        Object second = new Object();
        nfc.enableReaderMode(first);
        boolean quota = false;
        try {
            nfc.enableReaderMode(second);
        } catch (IllegalStateException expected) {
            quota = expected.getMessage().contains("LIMIT");
        }
        require(quota, "NFC reader quota enforced");
        nfc.disableReaderMode(first);
        nfc.enableReaderMode(second);
        require(nfc.getTagIds().length == 1, "NFC tag catalogue projected");
        require(nfc.transceive("tag-1").length == 0, "NFC tag operation virtualized");
        boolean unapproved = false;
        try {
            nfc.transceive("host-tag");
        } catch (SecurityException expected) {
            unapproved = expected.getMessage().contains("NOT_APPROVED");
        }
        require(unapproved, "unapproved NFC tag denied");
        boolean mutation = false;
        try {
            nfc.disable();
        } catch (SecurityException expected) {
            mutation = expected.getMessage().contains("MUTATION_DENIED");
        }
        require(mutation, "NFC adapter mutation denied");
    }

    private static void testUsb(GuestIdentity identity) {
        UsbApi usb = proxy(UsbApi.class, new UsbDelegate(), identity, "usb");
        require(usb.hasHostSupport() && usb.hasDevicePermission("usb-1"),
                "USB capability and permission projected");
        require(!usb.hasDevicePermission("host-usb"), "host USB device hidden");
        Object first = new Object();
        Object second = new Object();
        require(!usb.openDevice("usb-1", first).isEmpty(), "approved USB device opened virtually");
        boolean quota = false;
        try {
            usb.openDevice("usb-1", second);
        } catch (IllegalStateException expected) {
            quota = expected.getMessage().contains("LIMIT");
        }
        require(quota, "USB open-device quota enforced");
        usb.closeDevice(first);
        usb.openDevice("usb-1", second);
        boolean denied = false;
        try {
            usb.openDevice("host-usb", new Object());
        } catch (SecurityException expected) {
            denied = expected.getMessage().contains("OPEN_DENIED");
        }
        require(denied && "mtp".equals(usb.getCurrentFunctions()),
                "unapproved USB device denied and functions projected");
    }

    private static void testPrinting(GuestIdentity identity) {
        PrintApi printing = proxy(PrintApi.class, new PrintDelegate(), identity, "print");
        require(printing.isPrintingEnabled() && "printer-1".equals(printing.getDefaultPrinterId()),
                "printing state projected");
        Object first = new Object();
        Object second = new Object();
        printing.print("job-1", first);
        boolean quota = false;
        try {
            printing.print("job-2", second);
        } catch (IllegalStateException expected) {
            quota = expected.getMessage().contains("LIMIT");
        }
        require(quota, "print-job quota enforced");
        printing.cancelPrintJob(first);
        printing.print("job-2", second);
        require(printing.getPrintServices().length == 1, "print services projected");
    }

    private static void testCompanion(GuestIdentity identity) {
        CompanionApi companion = proxy(
                CompanionApi.class, new CompanionDelegate(), identity, "companiondevice");
        require(companion.getAssociations().length == 1,
                "companion associations projected");
        companion.disassociate("association-1");
        boolean associationDenied = false;
        try {
            companion.associate("watch");
        } catch (SecurityException expected) {
            associationDenied = expected.getMessage().contains("ASSOCIATION_DENIED");
        }
        require(associationDenied,
                "disassociation is classified independently from denied association");
        Object first = new Object();
        Object second = new Object();
        companion.startObservingDevicePresence(first);
        companion.startObservingDevicePresence(second);
        boolean observerQuota = false;
        try {
            companion.startObservingDevicePresence(new Object());
        } catch (IllegalStateException expected) {
            observerQuota = expected.getMessage().contains("LIMIT");
        }
        require(observerQuota, "companion observer quota enforced");
        companion.stopObservingDevicePresence(first);
    }

    private static void testProjection(GuestIdentity identity) {
        ProjectionApi projection = proxy(
                ProjectionApi.class, new ProjectionDelegate(), identity, "mediaprojection");
        require(projection.isAvailable() && projection.canScreenCapture()
                        && projection.getVirtualWidth() == 1080,
                "media projection state projected");
        Object first = new Object();
        Object second = new Object();
        projection.createProjection(first);
        boolean quota = false;
        try {
            projection.createProjection(second);
        } catch (IllegalStateException expected) {
            quota = expected.getMessage().contains("LIMIT");
        }
        require(quota, "media projection quota enforced");
        projection.stop(first);
        projection.createProjection(second);
    }

    private static void testCamera(GuestIdentity identity) {
        CameraApi camera = proxy(CameraApi.class, new CameraDelegate(), identity, "camera");
        require(camera.getCameraIdList().length == 2 && camera.isFrontCamera("1"),
                "camera catalogue projected");
        Object first = new Object();
        Object second = new Object();
        camera.openCamera("0", first, "guest.pkg");
        boolean quota = false;
        try {
            camera.openCamera("1", second, "guest.pkg");
        } catch (IllegalStateException expected) {
            quota = expected.getMessage().contains("LIMIT");
        }
        require(quota, "camera session quota enforced");
        camera.close(first);
        camera.openCamera("1", second, "guest.pkg");
        camera.setTorchMode("0", true, "guest.pkg");
        boolean hostCameraDenied = false;
        try {
            camera.openCamera("host-camera", new Object(), "guest.pkg");
        } catch (SecurityException expected) {
            hostCameraDenied = expected.getMessage().contains("OPEN_DENIED");
        }
        require(hostCameraDenied, "host camera identity hidden");
    }

    private static void testOem(GuestIdentity identity) {
        OemApi oem = proxy(OemApi.class, new OemDelegate(), identity, "oemsystem");
        require(oem.getState() instanceof List<?> values && values.isEmpty(),
                "OEM query returns deterministic empty value");
        boolean mutation = false;
        try {
            oem.setState("host");
        } catch (SecurityException expected) {
            mutation = expected.getMessage().contains("MUTATION_DENIED");
        }
        require(mutation, "OEM mutation denied");
        Object first = new Object();
        Object second = new Object();
        oem.openSession(first);
        boolean quota = false;
        try {
            oem.openSession(second);
        } catch (IllegalStateException expected) {
            quota = expected.getMessage().contains("LIMIT");
        }
        require(quota, "OEM session quota enforced");
        oem.closeSession(first);
        oem.openSession(second);
    }

    private static void testHostPassThrough() {
        NfcDelegate delegate = new NfcDelegate();
        NfcApi host = proxy(NfcApi.class, delegate, identity("HOST"), "nfc");
        require(host.getState() == 99 && delegate.calls == 1, "HOST NFC passes through");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T delegate, GuestIdentity identity, String service) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new SystemServiceInvocationHandler(delegate, identity, service));
    }

    private static GuestIdentity identity(String mode) {
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = "guest.pkg";
        app.uid = 12001;
        VirtualDeviceServiceProfileSnapshot device = new VirtualDeviceServiceProfileSnapshot(1L, 0L,
                new VirtualLocationProfileSnapshot("BLOCKED", "", false, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, false, 0, 0, ""),
                new VirtualDeviceIdentitySnapshot("STATIC", "0123456789abcdef", "serial",
                        "11111111-2222-3333-4444-555555555555", true, "install", "b", "m",
                        "model", "d", "p", "fp", "board", "hw"),
                new VirtualTelephonyProfileSnapshot("BLOCKED", -1, -1, false, false, false, List.of()),
                new VirtualWifiProfileSnapshot("BLOCKED", false, "", "", "", 0, -1, 0,
                        -127, 0, false, false, List.of()),
                new VirtualBluetoothProfileSnapshot("BLOCKED", false, 10, "", "", false,
                        List.of(), List.of()),
                new VirtualSensorProfileSnapshot("BLOCKED", 1, List.of()));
        VirtualPeripheralServicesProfileSnapshot peripheral = new VirtualPeripheralServicesProfileSnapshot(
                1L, 0L,
                new VirtualNfcProfileSnapshot(mode, "ON", true, true, false, 1, 2,
                        List.of("tag-1")),
                new VirtualUsbProfileSnapshot(mode, true, true, true, true, 1, "mtp",
                        List.of("usb-1"), List.of("accessory-1")),
                new VirtualPrintProfileSnapshot(mode, true, true, 1, "printer-1", "Office",
                        List.of("print.service")),
                new VirtualCompanionDeviceProfileSnapshot(mode, false, true, true, false, 2,
                        List.of("association-1"), List.of("watch")),
                new VirtualMediaProjectionProfileSnapshot(mode, true, true, false, false, 1,
                        1080, 1920, 420),
                new VirtualCameraProfileSnapshot(mode, true, true, true, 1,
                        List.of("0", "1"), List.of("1"), List.of("0")),
                new VirtualOemSystemServicesProfileSnapshot(mode, List.of("vendor.demo"),
                        List.of("get", "is"), List.of("set", "delete"), 1));
        return new GuestIdentity("guest.pkg", 12001, app, Set.of("android.permission.CAMERA"),
                "host.pkg", 10001, new VirtualPackageMetadata("guest.pkg", "", app, List.of()),
                "guest.pkg", 0, 1, new VirtualPermissionPolicy(Set.of("android.permission.CAMERA"), Map.of()),
                new SandboxAppOpsPolicy(Map.of("android:camera", SandboxAppOpsPolicy.ALLOWED)),
                event -> { }, new CapabilityLeaseRegistry(),
                new VirtualSystemServiceState(device, null, null, null, null, null, null, peripheral),
                "rev");
    }

    interface NfcApi {
        int getState();
        boolean isEnabled();
        void enableReaderMode(Object callback);
        void disableReaderMode(Object callback);
        String[] getTagIds();
        byte[] transceive(String tagId);
        boolean disable();
    }
    interface UsbApi {
        boolean hasHostSupport();
        boolean hasDevicePermission(String deviceName);
        String openDevice(String deviceName, Object token);
        void closeDevice(Object token);
        String getCurrentFunctions();
    }
    interface PrintApi {
        boolean isPrintingEnabled();
        String getDefaultPrinterId();
        String print(String jobName, Object token);
        void cancelPrintJob(Object token);
        String[] getPrintServices();
    }
    interface CompanionApi {
        String[] getAssociations();
        void associate(String profile);
        void disassociate(String associationId);
        void startObservingDevicePresence(Object callback);
        void stopObservingDevicePresence(Object callback);
    }
    interface ProjectionApi {
        boolean isAvailable();
        boolean canScreenCapture();
        int getVirtualWidth();
        String createProjection(Object token);
        void stop(Object token);
    }
    interface CameraApi {
        String[] getCameraIdList();
        boolean isFrontCamera(String cameraId);
        String openCamera(String cameraId, Object callback, String packageName);
        void close(Object callback);
        void setTorchMode(String cameraId, boolean enabled, String packageName);
    }
    interface OemApi {
        Object getState();
        void setState(String value);
        void openSession(Object callback);
        void closeSession(Object callback);
    }

    static final class NfcDelegate implements NfcApi {
        int calls;
        public int getState() { calls++; return 99; }
        public boolean isEnabled() { calls++; return false; }
        public void enableReaderMode(Object callback) { throw new AssertionError("delegate"); }
        public void disableReaderMode(Object callback) { throw new AssertionError("delegate"); }
        public String[] getTagIds() { return new String[]{"host"}; }
        public byte[] transceive(String tagId) { return new byte[]{1}; }
        public boolean disable() { return true; }
    }
    static final class UsbDelegate implements UsbApi {
        public boolean hasHostSupport() { return false; }
        public boolean hasDevicePermission(String deviceName) { return false; }
        public String openDevice(String deviceName, Object token) { return "host"; }
        public void closeDevice(Object token) { throw new AssertionError("delegate"); }
        public String getCurrentFunctions() { return "host"; }
    }
    static final class PrintDelegate implements PrintApi {
        public boolean isPrintingEnabled() { return false; }
        public String getDefaultPrinterId() { return "host"; }
        public String print(String jobName, Object token) { return "host"; }
        public void cancelPrintJob(Object token) { throw new AssertionError("delegate"); }
        public String[] getPrintServices() { return new String[]{"host"}; }
    }
    static final class CompanionDelegate implements CompanionApi {
        public String[] getAssociations() { return new String[]{"host"}; }
        public void associate(String profile) { throw new AssertionError("delegate"); }
        public void disassociate(String associationId) { throw new AssertionError("delegate"); }
        public void startObservingDevicePresence(Object callback) { throw new AssertionError("delegate"); }
        public void stopObservingDevicePresence(Object callback) { throw new AssertionError("delegate"); }
    }
    static final class ProjectionDelegate implements ProjectionApi {
        public boolean isAvailable() { return false; }
        public boolean canScreenCapture() { return false; }
        public int getVirtualWidth() { return 0; }
        public String createProjection(Object token) { return "host"; }
        public void stop(Object token) { throw new AssertionError("delegate"); }
    }
    static final class CameraDelegate implements CameraApi {
        public String[] getCameraIdList() { return new String[]{"host"}; }
        public boolean isFrontCamera(String cameraId) { return false; }
        public String openCamera(String cameraId, Object callback, String packageName) { return "host"; }
        public void close(Object callback) { throw new AssertionError("delegate"); }
        public void setTorchMode(String cameraId, boolean enabled, String packageName) {
            throw new AssertionError("delegate");
        }
    }
    static final class OemDelegate implements OemApi {
        public Object getState() { return new Object(); }
        public void setState(String value) { throw new AssertionError("delegate"); }
        public void openSession(Object callback) { throw new AssertionError("delegate"); }
        public void closeSession(Object callback) { throw new AssertionError("delegate"); }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
