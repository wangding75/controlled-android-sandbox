package com.warden.controlledsandbox.framework.service;

import android.content.pm.ApplicationInfo;
import android.hardware.ICameraService;
import android.hardware.camera2.CameraManager;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ServiceManager;

import com.warden.controlledsandbox.contract.*;
import com.warden.controlledsandbox.framework.contract.CameraServiceContract;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.*;
import com.warden.controlledsandbox.framework.packagemanager.PackageManagerInvocationHandlerTestAccess;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Camera contract coverage for API32/API35 service binding, cache sync and feature projection. */
public final class CameraServiceHookSelfTest {
    private CameraServiceHookSelfTest() { }

    public static void main(String[] args) throws Exception {
        testContractVersions();
        testExistingServiceAndCacheRollback();
        testMissingServiceUsesControlledSyntheticCamera();
        testInvalidDescriptorFailsClosed();
        testFeatureAndProfileConsistency();
        System.out.println("PASS Camera service contract self-test");
    }

    private static void testContractVersions() {
        check(CameraServiceContract.SERVICE_NAMES.equals(List.of("media.camera")),
                "API32/API35 must use the bounded media.camera service name");
        check("android.hardware.ICameraService".equals(CameraServiceContract.DESCRIPTOR),
                "API32/API35 must use the stable ICameraService descriptor");
        check(CameraServiceContract.CAMERA_MANAGER_GLOBAL_CLASS.endsWith(
                        "CameraManager$CameraManagerGlobal"),
                "CameraManagerGlobal nested class contract must be explicit");
        check("mCameraService".equals(CameraServiceContract.CAMERA_MANAGER_SERVICE_FIELD),
                "CameraManagerGlobal cache field must be explicit");
    }

    private static void testExistingServiceAndCacheRollback() throws Exception {
        ServiceManager.sCache.clear();
        TestCameraService service = new TestCameraService();
        TestBinder original = new TestBinder(CameraServiceContract.DESCRIPTOR, service);
        service.binder = original;
        ServiceManager.sCache.put(CameraServiceContract.SERVICE_NAME, original);
        CameraManager.setCachedServiceForTest(service);

        AutoCloseable hook = CameraServiceHook.install(null, identity("STATIC", true));
        IBinder replacement = ServiceManager.getService(CameraServiceContract.SERVICE_NAME);
        check(replacement != original, "Camera ServiceManager Binder must be proxied");
        check(CameraServiceContract.DESCRIPTOR.equals(replacement.getInterfaceDescriptor()),
                "Camera descriptor must remain stable");
        ICameraService projected = ICameraService.Stub.asInterface(replacement);
        check(projected != null && projected != service && projected.getNumberOfCameras(0) == 2,
                "Camera AIDL calls must enter the virtual profile proxy");
        check(CameraManager.cachedServiceForTest() != service
                        && CameraManager.cachedServiceForTest().getNumberOfCameras(0) == 2,
                "CameraManagerGlobal.mCameraService must be synchronized");

        AutoCloseable second = CameraServiceHook.install(null, identity("STATIC", true));
        check(ServiceManager.getService(CameraServiceContract.SERVICE_NAME) == replacement,
                "repeated Camera install must be idempotent");
        second.close();
        hook.close();
        check(ServiceManager.getService(CameraServiceContract.SERVICE_NAME) == original,
                "Camera ServiceManager rollback must restore original Binder");
        check(CameraManager.cachedServiceForTest() == service,
                "CameraManagerGlobal cache rollback must restore original interface");
    }

    private static void testMissingServiceUsesControlledSyntheticCamera() throws Exception {
        ServiceManager.sCache.clear();
        CameraManager.setCachedServiceForTest(null);
        AutoCloseable hook = CameraServiceHook.install(null, identity("STATIC", true));
        IBinder binder = ServiceManager.getService(CameraServiceContract.SERVICE_NAME);
        check(binder != null, "missing host Camera service must install a synthetic service");
        check(CameraServiceContract.DESCRIPTOR.equals(binder.getInterfaceDescriptor()),
                "synthetic Camera descriptor must be exact");
        ICameraService projected = ICameraService.Stub.asInterface(binder);
        check(projected != null && projected.getNumberOfCameras(0) == 2,
                "synthetic Camera service must expose the virtual profile contract");

        AutoCloseable second = CameraServiceHook.install(null, identity("STATIC", true));
        check(ServiceManager.getService(CameraServiceContract.SERVICE_NAME) == binder,
                "repeated synthetic Camera install must be idempotent");
        second.close();
        hook.close();
        check(ServiceManager.getService(CameraServiceContract.SERVICE_NAME) == null,
                "synthetic Camera rollback must remove the service");
    }

    private static void testInvalidDescriptorFailsClosed() {
        ServiceManager.sCache.clear();
        CameraManager.setCachedServiceForTest(null);
        TestBinder invalid = new TestBinder("invalid.camera.Descriptor", new TestCameraService());
        ServiceManager.sCache.put(CameraServiceContract.SERVICE_NAME, invalid);
        boolean rejected = false;
        try {
            CameraServiceHook.install(null, identity("STATIC", true));
        } catch (IllegalStateException error) {
            rejected = error.getMessage().contains("Unexpected Binder descriptor");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        check(rejected, "invalid Camera descriptor must fail closed");
        check(ServiceManager.getService(CameraServiceContract.SERVICE_NAME) == invalid,
                "invalid Camera descriptor must not mutate the cache");
    }

    private static void testFeatureAndProfileConsistency() {
        PackageManagerApi delegate = feature -> true;
        PackageManagerApi projected = packageManager(delegate, identity("STATIC", true));
        check(projected.hasSystemFeature(CameraServiceContract.FEATURE_CAMERA),
                "STATIC camera profile exposes FEATURE_CAMERA");
        check(projected.hasSystemFeature(CameraServiceContract.FEATURE_CAMERA_FRONT),
                "front camera IDs expose FEATURE_CAMERA_FRONT");
        projected = packageManager(delegate, identity("STATIC", false));
        check(!projected.hasSystemFeature(CameraServiceContract.FEATURE_CAMERA),
                "controlled-unavailable profile hides FEATURE_CAMERA");
        projected = packageManager(delegate, identity("BLOCKED", false));
        check(!projected.hasSystemFeature(CameraServiceContract.FEATURE_CAMERA),
                "BLOCKED camera profile hides FEATURE_CAMERA");
        projected = packageManager(delegate, identity("HOST", true));
        check(projected.hasSystemFeature(CameraServiceContract.FEATURE_CAMERA),
                "HOST camera delegates the host feature truth");
    }

    private static PackageManagerApi packageManager(PackageManagerApi delegate, GuestIdentity identity) {
        InvocationHandler handler = PackageManagerInvocationHandlerTestAccess.create(delegate, identity);
        return (PackageManagerApi) Proxy.newProxyInstance(PackageManagerApi.class.getClassLoader(),
                new Class<?>[]{PackageManagerApi.class}, handler);
    }

    private static GuestIdentity identity(String cameraMode, boolean available) {
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = "guest.pkg";
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
        VirtualPeripheralServicesProfileSnapshot peripheral =
                new VirtualPeripheralServicesProfileSnapshot(1L, 0L,
                        new VirtualNfcProfileSnapshot("BLOCKED", "OFF", false, false,
                                false, 0, 0, List.of()),
                        new VirtualUsbProfileSnapshot("BLOCKED", false, false, false, false,
                                0, "none", List.of(), List.of()),
                        new VirtualPrintProfileSnapshot("BLOCKED", false, false, 0, "", "",
                                List.of()),
                        new VirtualCompanionDeviceProfileSnapshot("BLOCKED", false, false, false,
                                false, 0, List.of(), List.of()),
                        new VirtualMediaProjectionProfileSnapshot("BLOCKED", false, false, false,
                                true, 0, 1080, 1920, 420),
                        new VirtualCameraProfileSnapshot(cameraMode, available, available, available,
                                available ? 1 : 0,
                                available ? List.of("0", "1") : List.of(),
                                available ? List.of("1") : List.of(),
                                available ? List.of("0") : List.of()),
                        new VirtualOemSystemServicesProfileSnapshot("BLOCKED", List.of(),
                                List.of("get"), List.of("set"), 0));
        return new GuestIdentity("guest.pkg", 12001, app, Set.of("android.permission.CAMERA"),
                "host.pkg", 10001, new VirtualPackageMetadata("guest.pkg", "", app, List.of()),
                "guest.pkg", 0, 1, new VirtualPermissionPolicy(
                        Set.of("android.permission.CAMERA"), Map.of()),
                new SandboxAppOpsPolicy(Map.of("android:camera", SandboxAppOpsPolicy.ALLOWED)),
                event -> { }, new CapabilityLeaseRegistry(),
                new VirtualSystemServiceState(device, null, null, null, null, null, null, peripheral),
                "rev");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public interface PackageManagerApi {
        boolean hasSystemFeature(String feature);
    }

    private static final class TestBinder implements IBinder {
        private final String descriptor;
        private final IInterface local;

        private TestBinder(String descriptor, IInterface local) {
            this.descriptor = descriptor;
            this.local = local;
        }

        @Override public String getInterfaceDescriptor() { return descriptor; }
        @Override public IInterface queryLocalInterface(String requested) {
            return descriptor.equals(requested) ? local : null;
        }
    }

    public static final class TestCameraService implements ICameraService {
        private IBinder binder;
        @Override public IBinder asBinder() { return binder; }
        @Override public int getNumberOfCameras(int type) { return 99; }
    }
}
