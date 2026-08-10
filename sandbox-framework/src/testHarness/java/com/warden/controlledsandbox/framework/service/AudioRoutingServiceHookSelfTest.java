package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import android.media.AudioManager;
import android.media.IAudioService;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ServiceManager;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import android.content.pm.ApplicationInfo;
import java.util.Set;

/** Regression coverage for the API32/API35 descriptor-bound AudioManager installation. */
public final class AudioRoutingServiceHookSelfTest {
    private static final String SERVICE = "audio";
    private static final String DESCRIPTOR = "android.media.IAudioService";

    private AudioRoutingServiceHookSelfTest() { }

    public static void main(String[] args) throws Exception {
        testApi32AndApi35StaticCacheContract();
        testInvalidDescriptorFailsClosed();
        testMissingServiceFailsClosed();
        System.out.println("PASS audio routing ServiceManager compatibility self-test");
    }

    private static void testApi32AndApi35StaticCacheContract() throws Exception {
        for (String apiContract : new String[]{"api32", "api35"}) {
            ServiceManager.sCache.clear();
            TestAudioService originalService = service();
            TestBinder originalBinder = new TestBinder(DESCRIPTOR, originalService);
            originalService.binder = originalBinder;
            ServiceManager.sCache.put(SERVICE, originalBinder);
            AudioManager.resetForTest(originalService);

            AutoCloseable hook = AudioCaptureServiceHook.install(
                    new TestContext(new AudioManager()), identity());
            IBinder replacement = ServiceManager.getService(SERVICE);
            check(replacement != originalBinder, apiContract + " audio Binder must be proxied");
            check(DESCRIPTOR.equals(replacement.getInterfaceDescriptor()),
                    apiContract + " descriptor must remain android.media.IAudioService");
            check(replacement.queryLocalInterface(DESCRIPTOR) != originalService,
                    apiContract + " AIDL local interface must enter sandbox proxy");
            check(AudioManager.serviceForTest() != originalService,
                    apiContract + " AudioManager.sService must be synchronized");

            AutoCloseable second = AudioCaptureServiceHook.install(
                    new TestContext(new AudioManager()), identity());
            check(ServiceManager.getService(SERVICE) == replacement,
                    apiContract + " repeated install must be idempotent");
            second.close();
            hook.close();
            check(ServiceManager.getService(SERVICE) == originalBinder,
                    apiContract + " rollback must restore ServiceManager cache");
            check(AudioManager.serviceForTest() == originalService,
                    apiContract + " rollback must restore AudioManager.sService");
        }
    }

    private static void testInvalidDescriptorFailsClosed() {
        ServiceManager.sCache.clear();
        TestBinder wrong = new TestBinder("invalid.AudioService", service());
        ServiceManager.sCache.put(SERVICE, wrong);
        AudioManager.resetForTest(null);
        boolean rejected = false;
        try {
            AudioCaptureServiceHook.install(new TestContext(new AudioManager()), identity());
        } catch (IllegalStateException error) {
            rejected = error.getMessage().contains("No supported Binder binding");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        check(rejected, "invalid audio descriptor must fail closed");
        check(ServiceManager.getService(SERVICE) == wrong,
                "invalid audio descriptor must not mutate cache");
    }

    private static void testMissingServiceFailsClosed() {
        ServiceManager.sCache.clear();
        AudioManager.resetForTest(null);
        boolean rejected = false;
        try {
            AudioCaptureServiceHook.install(new TestContext(new AudioManager()), identity());
        } catch (IllegalStateException error) {
            rejected = error.getMessage().contains("No supported Binder binding");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        check(rejected, "missing audio service and cache must fail closed");
        check(ServiceManager.getService(SERVICE) == null,
                "missing audio service must not synthesize readiness");
    }

    private static TestAudioService service() {
        return new TestAudioService();
    }

    private static GuestIdentity identity() {
        return new GuestIdentity("guest.pkg", 12001, new ApplicationInfo(), Set.of(),
                "host.pkg", 10001);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestContext extends Context {
        private final Object service;

        private TestContext(Object service) {
            this.service = service;
        }

        @Override public Object getSystemService(String name) {
            return service;
        }
    }

    public static final class TestBinder implements IBinder {
        private final String descriptor;
        private final IInterface local;

        private TestBinder(String descriptor, IInterface local) {
            this.descriptor = descriptor;
            this.local = local;
        }

        @Override public String getInterfaceDescriptor() {
            return descriptor;
        }

        @Override public IInterface queryLocalInterface(String requested) {
            return descriptor.equals(requested) ? local : null;
        }
    }

    public static final class TestAudioService implements IAudioService {
        private IBinder binder;

        @Override public IBinder asBinder() {
            return binder;
        }

        @Override public void routeProbe() { }
    }
}
