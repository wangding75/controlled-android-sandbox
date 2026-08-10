package com.warden.controlledsandbox.framework.service;

import android.content.pm.ApplicationInfo;
import android.nfc.INfcAdapter;
import android.nfc.NfcAdapter;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ServiceManager;

import com.warden.controlledsandbox.contract.*;
import com.warden.controlledsandbox.framework.core.NfcServiceContract;
import com.warden.controlledsandbox.framework.identity.*;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.packagemanager.PackageManagerInvocationHandlerTestAccess;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** NFC contract coverage for descriptor validation, synthetic fallback, cache sync and feature policy. */
public final class NfcServiceHookSelfTest {
    private NfcServiceHookSelfTest() { }

    public static void main(String[] args) throws Exception {
        testContractVersions();
        testExistingServiceAndCacheRollback();
        testMissingServiceUsesControlledSyntheticAdapter();
        testInvalidDescriptorFailsClosed();
        testFeatureAndAdapterStateConsistency();
        System.out.println("PASS NFC service contract self-test");
    }

    private static void testContractVersions() {
        check(NfcServiceContract.SERVICE_NAMES.equals(List.of("nfc")),
                "API32/API35 must use the bounded nfc service name");
        check("android.nfc.INfcAdapter".equals(NfcServiceContract.DESCRIPTOR),
                "API32/API35 must use the stable INfcAdapter descriptor");
    }

    private static void testExistingServiceAndCacheRollback() throws Exception {
        ServiceManager.sCache.clear();
        TestNfcService service = new TestNfcService();
        TestBinder original = new TestBinder(NfcServiceContract.DESCRIPTOR, service);
        service.binder = original;
        ServiceManager.sCache.put(NfcServiceContract.SERVICE_NAME, original);
        NfcAdapter.sService = service;

        AutoCloseable hook = NfcServiceHook.install(identity("STATIC", "ON"));
        IBinder replacement = ServiceManager.getService(NfcServiceContract.SERVICE_NAME);
        check(replacement != original, "NFC ServiceManager Binder must be proxied");
        check(NfcServiceContract.DESCRIPTOR.equals(replacement.getInterfaceDescriptor()),
                "NFC descriptor must remain stable");
        INfcAdapter projected = INfcAdapter.Stub.asInterface(replacement);
        check(projected != null && projected != service && projected.getState() == 3
                        && projected.isEnabled(),
                "NFC AIDL calls must enter the virtual service proxy");
        check(NfcAdapter.sService != service && NfcAdapter.sService.getState() == 3,
                "NfcAdapter.sService must be synchronized when populated");

        AutoCloseable second = NfcServiceHook.install(identity("STATIC", "ON"));
        check(ServiceManager.getService(NfcServiceContract.SERVICE_NAME) == replacement,
                "repeated NFC install must be idempotent");
        second.close();
        hook.close();
        check(ServiceManager.getService(NfcServiceContract.SERVICE_NAME) == original,
                "NFC ServiceManager rollback must restore original Binder");
        check(NfcAdapter.sService == service,
                "NfcAdapter cache rollback must restore original interface");
    }

    private static void testMissingServiceUsesControlledSyntheticAdapter() throws Exception {
        ServiceManager.sCache.clear();
        NfcAdapter.sService = null;
        AutoCloseable hook = NfcServiceHook.install(identity("STATIC", "ON"));
        IBinder binder = ServiceManager.getService(NfcServiceContract.SERVICE_NAME);
        check(binder != null, "missing host NFC must install a synthetic service");
        check(NfcServiceContract.DESCRIPTOR.equals(binder.getInterfaceDescriptor()),
                "synthetic NFC descriptor must be exact");
        INfcAdapter projected = INfcAdapter.Stub.asInterface(binder);
        check(projected != null && projected.getState() == 3,
                "synthetic NFC service must expose the virtual adapter contract");

        AutoCloseable second = NfcServiceHook.install(identity("STATIC", "ON"));
        check(ServiceManager.getService(NfcServiceContract.SERVICE_NAME) == binder,
                "repeated synthetic NFC install must be idempotent");
        second.close();
        hook.close();
        check(ServiceManager.getService(NfcServiceContract.SERVICE_NAME) == null,
                "synthetic NFC rollback must remove the service");
    }

    private static void testInvalidDescriptorFailsClosed() {
        ServiceManager.sCache.clear();
        NfcAdapter.sService = null;
        TestBinder invalid = new TestBinder("invalid.nfc.Descriptor", new TestNfcService());
        ServiceManager.sCache.put(NfcServiceContract.SERVICE_NAME, invalid);
        boolean rejected = false;
        try {
            NfcServiceHook.install(identity("STATIC", "ON"));
        } catch (IllegalStateException error) {
            rejected = error.getMessage().contains("Unexpected Binder descriptor");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        check(rejected, "invalid NFC descriptor must fail closed");
        check(ServiceManager.getService(NfcServiceContract.SERVICE_NAME) == invalid,
                "invalid NFC descriptor must not mutate the cache");
    }

    private static void testFeatureAndAdapterStateConsistency() throws Exception {
        ServiceManager.sCache.clear();
        NfcAdapter.sService = null;
        NfcServiceHook.install(identity("STATIC", "OFF")).close();
        // The hook is intentionally closed above; inspect the virtual feature policy independently.
        PackageManagerApi delegate = feature -> true;
        PackageManagerApi projected = packageManager(delegate, identity("STATIC", "OFF"));
        check(projected.hasSystemFeature(NfcServiceContract.FEATURE_NFC),
                "STATIC NFC exposes a virtual capability even when adapter state is OFF");
        projected = packageManager(delegate, identity("BLOCKED", "OFF"));
        check(!projected.hasSystemFeature(NfcServiceContract.FEATURE_NFC),
                "BLOCKED NFC hides FEATURE_NFC");
        projected = packageManager(delegate, identity("HOST", "OFF"));
        check(projected.hasSystemFeature(NfcServiceContract.FEATURE_NFC),
                "HOST NFC delegates the host feature truth");
    }

    private static PackageManagerApi packageManager(PackageManagerApi delegate, GuestIdentity identity) {
        InvocationHandler handler = PackageManagerInvocationHandlerTestAccess.create(delegate, identity);
        return (PackageManagerApi) Proxy.newProxyInstance(PackageManagerApi.class.getClassLoader(),
                new Class<?>[] {PackageManagerApi.class}, handler);
    }

    private static GuestIdentity identity(String nfcMode, String adapterState) {
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
        VirtualPeripheralServicesProfileSnapshot peripheral = new VirtualPeripheralServicesProfileSnapshot(
                1L, 0L,
                new VirtualNfcProfileSnapshot(nfcMode, adapterState,
                        false, false, false, 0, 0, List.of()),
                new VirtualUsbProfileSnapshot("BLOCKED", false, false, false, false, 0,
                        "none", List.of(), List.of()),
                new VirtualPrintProfileSnapshot("BLOCKED", false, false, 0, "", "", List.of()),
                new VirtualCompanionDeviceProfileSnapshot("BLOCKED", false, false, false,
                        false, 0, List.of(), List.of()),
                new VirtualMediaProjectionProfileSnapshot("BLOCKED", false, false, false,
                        true, 0, 1080, 1920, 420),
                new VirtualCameraProfileSnapshot("BLOCKED", false, false, false, 0,
                        List.of(), List.of(), List.of()),
                new VirtualOemSystemServicesProfileSnapshot("BLOCKED", List.of(), List.of("get"),
                        List.of("set"), 0));
        return new GuestIdentity("guest.pkg", 12001, app, Set.of(), "host.pkg", 10001,
                new VirtualPackageMetadata("guest.pkg", "", app, List.of()), "guest.pkg", 0, 1,
                new VirtualPermissionPolicy(Set.of(), Map.of()),
                new SandboxAppOpsPolicy(Map.of()), event -> { }, new CapabilityLeaseRegistry(),
                new VirtualSystemServiceState(device, null, null, null, null, null, null,
                        peripheral), "rev");
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

    public static final class TestNfcService implements INfcAdapter {
        private IBinder binder;
        @Override public IBinder asBinder() { return binder; }
        @Override public int getState() { return 99; }
        @Override public boolean isEnabled() { return true; }
    }
}
