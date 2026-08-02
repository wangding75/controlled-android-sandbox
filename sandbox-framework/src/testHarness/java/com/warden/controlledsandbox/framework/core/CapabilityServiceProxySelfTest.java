package com.warden.controlledsandbox.framework.core;

import android.content.pm.ApplicationInfo;
import com.warden.controlledsandbox.framework.capability.CapabilityAuditEvent;
import com.warden.controlledsandbox.framework.capability.CapabilityAuditSink;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPermissionPolicy;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-side tests for bounded camera/location/microphone method gates and revocation cleanup. */
public final class CapabilityServiceProxySelfTest {
    public static void main(String[] args) {
        testDeniedCameraAndMicrophone();
        testLocationLeaseRevoked();
        testComplexLocationSignatureTracksListener();
        testCameraDeviceRevoked();
        testLeaseCapacityAndReplacementCleanup();
        testFatalCleanupEscapes();
        System.out.println("PASS capability service proxy and revocation self-test");
    }

    private static void testDeniedCameraAndMicrophone() {
        Fixture fixture = fixture(Set.of(), Map.of());
        FakeCameraService cameraDelegate = new FakeCameraService();
        CameraApi camera = proxy(CameraApi.class, cameraDelegate, fixture.identity, "camera");
        boolean denied = false;
        try { camera.openCamera("0", "guest.pkg"); }
        catch (SecurityException expected) { denied = expected.getMessage().contains("camera"); }
        require(denied && cameraDelegate.calls == 0, "camera denied before host delegate");

        FakeAudioService audioDelegate = new FakeAudioService();
        AudioApi audio = proxy(AudioApi.class, audioDelegate, fixture.identity, "audio");
        denied = false;
        try { audio.startRecording("guest.pkg"); }
        catch (SecurityException expected) { denied = expected.getMessage().contains("microphone"); }
        require(denied && audioDelegate.calls == 0, "microphone denied before host delegate");
        require(fixture.events.stream().anyMatch(e -> "DENIED".equals(e.decision())),
                "denied calls audited");
    }

    private static void testLocationLeaseRevoked() {
        Fixture fixture = fixture(Set.of("android.permission.ACCESS_FINE_LOCATION"),
                Map.of("android:fine_location", "ALLOWED"));
        FakeLocationService delegate = new FakeLocationService();
        LocationApi location = proxy(LocationApi.class, delegate, fixture.identity, "location");
        Listener listener = new Listener();
        location.requestLocationUpdates("gps", listener, "guest.pkg");
        require(delegate.requests == 1 && fixture.leases.activeCount("location") == 1,
                "location listener lease tracked");
        fixture.permissions.replace(Set.of("android.permission.ACCESS_FINE_LOCATION"), Map.of(), Set.of());
        int revoked = fixture.leases.revokeDenied(fixture.identity.capabilityPolicy(), fixture.events::add);
        require(revoked == 1 && delegate.removals == 1 && fixture.leases.activeCount() == 0,
                "location listener actively removed on revoke");
    }

    private static void testComplexLocationSignatureTracksListener() {
        Fixture fixture = fixture(Set.of("android.permission.ACCESS_FINE_LOCATION"),
                Map.of("android:fine_location", "ALLOWED"));
        FakeComplexLocationService delegate = new FakeComplexLocationService();
        ComplexLocationApi location = proxy(ComplexLocationApi.class, delegate, fixture.identity, "location");
        Listener listener = new Listener();
        location.requestLocationUpdates(new FakeLocationRequest(), new FakeExecutor(), listener, "guest.pkg");
        require(fixture.leases.activeCount("location") == 1,
                "complex signature tracks listener rather than request or executor");
        fixture.permissions.replace(Set.of("android.permission.ACCESS_FINE_LOCATION"), Map.of(), Set.of());
        fixture.leases.revokeDenied(fixture.identity.capabilityPolicy(), fixture.events::add);
        require(delegate.removals == 1, "complex listener cleanup invokes matching remove method");
    }

    private static void testCameraDeviceRevoked() {
        Fixture fixture = fixture(Set.of("android.permission.CAMERA"),
                Map.of("android:camera", "ALLOWED"));
        FakeCameraService delegate = new FakeCameraService();
        CameraApi camera = proxy(CameraApi.class, delegate, fixture.identity, "camera");
        CameraDevice device = camera.openCamera("0", "guest.pkg");
        require(delegate.calls == 1 && fixture.leases.activeCount("camera") == 1,
                "camera device lease tracked");
        fixture.appOps.replace(Map.of("android:camera", "IGNORED"));
        fixture.leases.revokeDenied(fixture.identity.capabilityPolicy(), fixture.events::add);
        require(device.closed && fixture.leases.activeCount() == 0,
                "camera device closed after AppOps revocation");
    }

    private static void testLeaseCapacityAndReplacementCleanup() {
        CapabilityLeaseRegistry leases = new CapabilityLeaseRegistry();
        java.util.concurrent.atomic.AtomicInteger cleanups = new java.util.concurrent.atomic.AtomicInteger();
        Object replacement = new Object();
        leases.register("camera", replacement, cleanups::incrementAndGet);
        leases.register("camera", replacement, cleanups::incrementAndGet);
        require(cleanups.get() == 1 && leases.activeCount() == 1,
                "replacing a capability token releases the old resource");
        for (int index = 1; index < CapabilityLeaseRegistry.MAX_ACTIVE_LEASES; index++) {
            leases.register("camera", new Object(), cleanups::incrementAndGet);
        }
        boolean bounded = false;
        try { leases.register("camera", new Object(), cleanups::incrementAndGet); }
        catch (IllegalStateException expected) { bounded = true; }
        require(bounded && cleanups.get() == 2,
                "capacity overflow cleans the rejected resource and fails closed");
        leases.close();
    }


    private static void testFatalCleanupEscapes() {
        CapabilityLeaseRegistry leases = new CapabilityLeaseRegistry();
        Object token = new Object();
        leases.register("camera", token, () -> { throw new AssertionError("fatal-cleanup"); });
        boolean escaped = false;
        try { leases.release(token, CapabilityAuditSink.NO_OP, "TEST"); }
        catch (AssertionError expected) { escaped = true; }
        require(escaped, "capability cleanup converted Error into audit-only failure");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> api, T delegate, GuestIdentity identity, String service) {
        return (T) Proxy.newProxyInstance(CapabilityServiceProxySelfTest.class.getClassLoader(),
                new Class<?>[]{api}, new SystemServiceInvocationHandler(delegate, identity, service));
    }

    private static Fixture fixture(Set<String> effective, Map<String, String> appOpModes) {
        Set<String> declared = Set.of("android.permission.CAMERA", "android.permission.RECORD_AUDIO",
                "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION");
        VirtualPermissionPolicy permissions = new VirtualPermissionPolicy(declared, Map.of(), effective);
        SandboxAppOpsPolicy appOps = new SandboxAppOpsPolicy(appOpModes);
        CapabilityLeaseRegistry leases = new CapabilityLeaseRegistry();
        List<CapabilityAuditEvent> events = new ArrayList<>();
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = "guest.pkg";
        info.uid = 12001;
        GuestIdentity identity = new GuestIdentity("guest.pkg", 12001, info, declared,
                "host.pkg", 10001, new VirtualPackageMetadata("guest.pkg", "", info, List.of()),
                "guest.pkg", 0, 3L, permissions, appOps, events::add, leases);
        return new Fixture(identity, permissions, appOps, leases, events);
    }

    interface CameraApi { CameraDevice openCamera(String id, String packageName); }
    static final class FakeCameraService implements CameraApi {
        int calls;
        @Override public CameraDevice openCamera(String id, String packageName) { calls++; return new CameraDevice(); }
    }
    static final class CameraDevice {
        boolean closed;
        public void close() { closed = true; }
    }

    interface LocationApi {
        void requestLocationUpdates(String provider, Listener listener, String packageName);
        void removeUpdates(Listener listener);
    }
    static final class FakeLocationService implements LocationApi {
        int requests;
        int removals;
        @Override public void requestLocationUpdates(String provider, Listener listener, String packageName) {
            requests++;
        }
        @Override public void removeUpdates(Listener listener) { removals++; }
    }
    static final class Listener { }

    interface ComplexLocationApi {
        void requestLocationUpdates(FakeLocationRequest request, FakeExecutor executor,
                                    Listener listener, String packageName);
        void removeUpdates(Listener listener);
    }
    static final class FakeComplexLocationService implements ComplexLocationApi {
        int removals;
        @Override public void requestLocationUpdates(FakeLocationRequest request, FakeExecutor executor,
                                                     Listener listener, String packageName) { }
        @Override public void removeUpdates(Listener listener) { removals++; }
    }
    static final class FakeLocationRequest { }
    static final class FakeExecutor { }

    interface AudioApi { int startRecording(String packageName); }
    static final class FakeAudioService implements AudioApi {
        int calls;
        @Override public int startRecording(String packageName) { calls++; return 7; }
    }

    private record Fixture(GuestIdentity identity, VirtualPermissionPolicy permissions,
                           SandboxAppOpsPolicy appOps, CapabilityLeaseRegistry leases,
                           List<CapabilityAuditEvent> events) { }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
