package com.warden.controlledsandbox.framework.service;

import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ServiceManager;
import android.telephony.TelephonyManager;
import com.android.internal.telephony.ISms;
import com.warden.controlledsandbox.framework.core.SmsServiceContract;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.util.Set;

/** Regression coverage for SMS service resolution, cache sync, rollback and synthetic fallback. */
public final class SmsServiceHookSelfTest {
    private SmsServiceHookSelfTest() { }

    public static void main(String[] args) throws Exception {
        testExistingServiceAndCacheRollback();
        testMissingServiceUsesBoundedSyntheticContract();
        testInvalidDescriptorFailsClosed();
        System.out.println("PASS SMS service contract self-test");
    }

    private static void testExistingServiceAndCacheRollback() throws Exception {
        ServiceManager.sCache.clear();
        TestSmsService service = new TestSmsService();
        TestBinder original = new TestBinder(SmsServiceContract.DESCRIPTOR, service);
        service.binder = original;
        ServiceManager.sCache.put("isms", original);
        TelephonyManager.resetSmsServiceForTest(service);

        AutoCloseable hook = SmsServiceHook.install(identity());
        IBinder replacement = ServiceManager.getService("isms");
        check(replacement != original, "SMS ServiceManager Binder must be proxied");
        check(SmsServiceContract.DESCRIPTOR.equals(replacement.getInterfaceDescriptor()),
                "SMS descriptor must remain stable");
        ISms projected = ISms.Stub.asInterface(replacement);
        check(projected != service && "host-proxy".equals(projected.probe()),
                "SMS AIDL calls must enter the common service proxy");
        check(TelephonyManager.smsServiceForTest() != service,
                "TelephonyManager.sISms must be synchronized when populated");

        AutoCloseable second = SmsServiceHook.install(identity());
        check(ServiceManager.getService("isms") == replacement,
                "repeated SMS install must be idempotent");
        second.close();
        hook.close();
        check(ServiceManager.getService("isms") == original,
                "SMS ServiceManager rollback must restore original Binder");
        check(TelephonyManager.smsServiceForTest() == service,
                "SMS cache rollback must restore original interface");
    }

    private static void testMissingServiceUsesBoundedSyntheticContract() throws Exception {
        ServiceManager.sCache.clear();
        TelephonyManager.resetSmsServiceForTest(null);
        AutoCloseable hook = SmsServiceHook.install(identity());
        for (String name : SmsServiceContract.SERVICE_NAMES) {
            IBinder binder = ServiceManager.getService(name);
            check(binder != null, "bounded SMS alias must be installed: " + name);
            check(SmsServiceContract.DESCRIPTOR.equals(binder.getInterfaceDescriptor()),
                    "synthetic SMS descriptor must be exact: " + name);
            check(ISms.Stub.asInterface(binder) != null,
                    "synthetic SMS local interface must resolve: " + name);
        }
        hook.close();
        for (String name : SmsServiceContract.SERVICE_NAMES) {
            check(ServiceManager.getService(name) == null,
                    "synthetic SMS alias rollback must remove: " + name);
        }
    }

    private static void testInvalidDescriptorFailsClosed() {
        ServiceManager.sCache.clear();
        TelephonyManager.resetSmsServiceForTest(null);
        TestBinder invalid = new TestBinder("invalid.sms.Descriptor", new TestSmsService());
        ServiceManager.sCache.put("isms", invalid);
        boolean rejected = false;
        try {
            SmsServiceHook.install(identity());
        } catch (IllegalStateException error) {
            rejected = error.getMessage().contains("Unexpected Binder descriptor");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        check(rejected, "invalid SMS descriptor must fail closed");
        check(ServiceManager.getService("isms") == invalid,
                "invalid SMS descriptor must not mutate the cache");
    }

    private static GuestIdentity identity() {
        return new GuestIdentity("guest.pkg", 12001, new ApplicationInfo(), Set.of(),
                "host.pkg", 10001);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
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

    public static final class TestSmsService implements ISms {
        private IBinder binder;
        @Override public IBinder asBinder() { return binder; }
        @Override public String probe() { return "host-proxy"; }
    }
}
