package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.ServiceManager;
import com.warden.controlledsandbox.contract.VirtualBluetoothProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDisplayProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDisplaySnapshot;
import com.warden.controlledsandbox.contract.VirtualInputMethodProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWindowPolicySnapshot;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPermissionPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Installer-level proof for ActivityClient and DisplayManager proxy/cache synchronization. */
public final class InteractionProxyInstallationSelfTest {
    private static final String DISPLAY = "display";
    private static final String DISPLAY_DESCRIPTOR = "android.hardware.display.IDisplayManager";

    private InteractionProxyInstallationSelfTest() { }

    public static void main(String[] args) throws Exception {
        testActivityClientApi32And35Contract();
        testDisplayInstallCacheInvocationAndRollback();
        testDisplayDescriptorMismatchFailsClosed();
        System.out.println("PASS interaction proxy installation self-test");
    }

    private static void testActivityClientApi32And35Contract() throws Exception {
        GuestIdentity identity = identity();
        android.app.ActivityClient.Controller original = new android.app.ActivityClient.Controller();
        android.app.ActivityClient.resetForTest(original);
        AutoCloseable first = ActivityClientHook.install(identity);
        com.warden.controlledsandbox.framework.identity.GuestInteractionState.InvocationState trace =
                identity.interactions().invocations();
        android.app.ActivityClient.IActivityClientController service =
                android.app.ActivityClient.getServiceForTest();
        service.activityResumed(new Binder(), true);
        service.activityDestroyed(new Binder());
        require(original.calls() == 2, "ActivityClient call reaches the host delegate once");
        require(trace.count("activityClient") == 2,
                "ActivityClient invocation enters the installed proxy");
        require(android.app.ActivityClient.instanceForTest() == service
                        && android.app.ActivityClient.knownInstanceForTest() == service,
                "ActivityClient mInstance and mKnownInstance are synchronized");

        AutoCloseable second = ActivityClientHook.install(identity);
        second.close();
        require(android.app.ActivityClient.getServiceForTest() == service,
                "duplicate ActivityClient install is idempotent");
        first.close();
        require(android.app.ActivityClient.instanceForTest() == original
                        && android.app.ActivityClient.knownInstanceForTest() == original,
                "ActivityClient rollback restores both cache values");
        identity.interactions().close();
    }

    private static void testDisplayInstallCacheInvocationAndRollback() throws Exception {
        GuestIdentity identity = identity();
        DisplayController original = new DisplayController();
        ServiceManager.sCache.clear();
        ServiceManager.sCache.put(DISPLAY, original);
        android.hardware.display.DisplayManagerGlobal global =
                new android.hardware.display.DisplayManagerGlobal(original);
        android.hardware.display.DisplayManagerGlobal.setInstanceForTest(global);
        TestContext context = new TestContext(new android.hardware.display.DisplayManager(global));
        List<Object> originalInfo = global.infoCacheForTest();
        int[] originalIds = global.idCacheForTest();
        List<Object> originalDisplays = context.manager.displaysForTest();

        AutoCloseable first = DisplayManagerHook.install(context, identity);
        android.hardware.display.IDisplayManager service = global.serviceForTest();
        int[] ids = service.getDisplayIds();
        require(ids.length == 1 && ids[0] == 7, "display IDs are guest projected");
        require(original.calls == 0, "virtual display query does not leak to host Binder");
        require(identity.interactions().invocations().invoked("display"),
                "DisplayManager call enters the installed proxy");
        require(global.infoCacheForTest().isEmpty() && global.idCacheForTest().length == 0,
                "DisplayManagerGlobal caches are emptied during install");
        require(context.manager.displaysForTest().isEmpty(),
                "DisplayManager manager cache is emptied during install");
        require(ServiceManager.getService(DISPLAY) != original,
                "ServiceManager cache contains the descriptor-bound proxy");
        require(DISPLAY_DESCRIPTOR.equals(ServiceManager.getService(DISPLAY)
                        .getInterfaceDescriptor()), "display Binder descriptor is validated");

        AutoCloseable second = DisplayManagerHook.install(context, identity);
        second.close();
        require(global.serviceForTest() == service,
                "duplicate DisplayManager install is idempotent");
        first.close();
        require(global.serviceForTest() == original && ServiceManager.getService(DISPLAY) == original,
                "DisplayManager rollback restores manager and ServiceManager Binder");
        require(global.infoCacheForTest() == originalInfo && global.idCacheForTest() == originalIds,
                "DisplayManagerGlobal cache values are restored");
        require(context.manager.displaysForTest() == originalDisplays,
                "DisplayManager cache value is restored");
        identity.interactions().close();
    }

    private static void testDisplayDescriptorMismatchFailsClosed() {
        GuestIdentity identity = identity();
        DisplayController original = new DisplayController();
        ServiceManager.sCache.clear();
        ServiceManager.sCache.put(DISPLAY, new Binder() {
            @Override public String getInterfaceDescriptor() { return "wrong.descriptor"; }
        });
        android.hardware.display.DisplayManagerGlobal global =
                new android.hardware.display.DisplayManagerGlobal(original);
        android.hardware.display.DisplayManagerGlobal.setInstanceForTest(global);
        TestContext context = new TestContext(new android.hardware.display.DisplayManager(global));
        boolean failed = false;
        try { DisplayManagerHook.install(context, identity); }
        catch (IllegalStateException expected) {
            failed = expected.getMessage().contains("Unexpected Binder descriptor")
                    || expected.getMessage().contains("No supported Binder binding");
        } catch (Exception expected) {
            failed = expected.getMessage() != null
                    && expected.getMessage().contains("descriptor");
        }
        require(failed, "invalid display Binder descriptor fails closed");
        require(global.serviceForTest() == original,
                "invalid descriptor leaves the manager untouched");
        identity.interactions().close();
    }

    private static GuestIdentity identity() {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = "guest.pkg";
        info.uid = 12001;
        Set<String> permissions = Set.of();
        return new GuestIdentity("guest.pkg", 12001, info, permissions, "host.pkg", 10001,
                new VirtualPackageMetadata("guest.pkg", "", info, List.of()), "guest.pkg", 0,
                9L, new VirtualPermissionPolicy(permissions, Map.of(), permissions),
                new SandboxAppOpsPolicy(Map.of()), event -> { }, new CapabilityLeaseRegistry(),
                new VirtualSystemServiceState(deviceProfile(), interactionProfile()),
                "revision-t39");
    }

    private static VirtualInteractionProfileSnapshot interactionProfile() {
        String mode = VirtualWindowPolicySnapshot.MODE_STATIC;
        VirtualWindowPolicySnapshot window = new VirtualWindowPolicySnapshot(mode, 2,
                true, false, false, false);
        VirtualInputMethodProfileSnapshot input = new VirtualInputMethodProfileSnapshot(mode,
                "", List.of(), false, false, false, true, 2);
        VirtualDisplaySnapshot display = new VirtualDisplaySnapshot(7, "Virtual display",
                1440, 2560, 560, 560f, 560f, 60f, 1, 2, 0, true);
        VirtualDisplayProfileSnapshot displays = new VirtualDisplayProfileSnapshot(mode, 7,
                false, 0, List.of(display));
        return new VirtualInteractionProfileSnapshot(1L, 1L, window, input, displays);
    }

    private static VirtualDeviceServiceProfileSnapshot deviceProfile() {
        String mode = VirtualLocationProfileSnapshot.MODE_HOST;
        return new VirtualDeviceServiceProfileSnapshot(1L, 1L,
                new VirtualLocationProfileSnapshot(mode, "gps", false, 0, 0, 0, 1f,
                        0, 0, 0, 0, 1000, false, 0, 0, ""),
                new VirtualDeviceIdentitySnapshot(mode, "", "", "", false, "", "", "",
                        "", "", "", "", "", ""),
                new VirtualTelephonyProfileSnapshot(mode, -1, -1, false, false, false, List.of()),
                new VirtualWifiProfileSnapshot(mode, false, "", "", "", 0, -1, 0,
                        -127, 0, false, false, List.of()),
                new VirtualBluetoothProfileSnapshot(mode, false, 10, "", "", false,
                        List.of(), List.of()),
                new VirtualSensorProfileSnapshot(mode, 60, List.of()));
    }

    private static final class TestContext extends Context {
        private final android.hardware.display.DisplayManager manager;
        private TestContext(android.hardware.display.DisplayManager manager) { this.manager = manager; }
        @Override public Object getSystemService(String name) {
            return DISPLAY.equals(name) ? manager : super.getSystemService(name);
        }
    }

    private static final class DisplayController extends android.hardware.display.IDisplayManager.Stub {
        private int calls;

        private DisplayController() { attachInterface(this, DISPLAY_DESCRIPTOR); }
        @Override public IBinder asBinder() { return this; }
        @Override public String getInterfaceDescriptor() { return DISPLAY_DESCRIPTOR; }
        @Override public int[] getDisplayIds() { calls++; return new int[] {99}; }
        @Override public Object getDisplayInfo(int displayId) { calls++; return "host-info"; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
