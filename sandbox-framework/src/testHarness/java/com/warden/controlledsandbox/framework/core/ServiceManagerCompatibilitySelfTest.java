package com.warden.controlledsandbox.framework.core;

import android.accounts.IAccountManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ServiceManager;
import android.os.storage.IStorageManager;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import java.util.List;
import java.util.Set;

/** Regression coverage for descriptor-checked ServiceManager and legacy manager bindings. */
public final class ServiceManagerCompatibilitySelfTest {
    private static final List<ServiceCase> CASES = List.of(
            new ServiceCase("account", "android.accounts.IAccountManager", "mService"),
            new ServiceCase("storage", "android.os.storage.IStorageManager", "mStorageManager"));

    private ServiceManagerCompatibilitySelfTest() { }

    public static void main(String[] args) throws Exception {
        testServiceManagerBindingAndRollback();
        testStorageMountServiceAliasAndRollback();
        testLegacyManagerFieldFallbackAndRollback();
        testInvalidDescriptorFailsClosed();
        testUnsupportedBindingFailsClosed();
        System.out.println("PASS framework service manager compatibility self-test");
    }

    private static void testServiceManagerBindingAndRollback() throws Exception {
        for (ServiceCase item : CASES) {
            ServiceManager.sCache.clear();
            TestService service = new TestService(item.descriptor());
            TestBinder original = new TestBinder(item.descriptor(), service);
            service.binder = original;
            ServiceManager.sCache.put(item.serviceName(), original);

            AutoCloseable hook = ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                    new TestContext(null), item.serviceName(), item.serviceName(),
                    item.descriptor(), identity(), item.fieldName());
            IBinder replacement = ServiceManager.getService(item.serviceName());
            check(replacement != original, item.serviceName() + " ServiceManager cache must be replaced");
            check(item.probe(replacement).equals("host-proxy"),
                    item.serviceName() + " framework call must enter proxy");

            hook.close();
            check(ServiceManager.getService(item.serviceName()) == original,
                    item.serviceName() + " ServiceManager rollback must restore original");
        }
    }

    private static void testLegacyManagerFieldFallbackAndRollback() throws Exception {
        for (ServiceCase item : CASES) {
            ServiceManager.sCache.clear();
            TestService service = new TestService(item.descriptor());
            TestBinder original = new TestBinder(item.descriptor(), service);
            service.binder = original;
            LegacyManager manager = new LegacyManager(item, service);

            AutoCloseable hook = ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                    new TestContext(manager), item.serviceName(), item.serviceName(),
                    item.descriptor(), identity(), item.fieldName());
            check(manager.probe(item).equals("host-proxy"),
                    item.serviceName() + " legacy manager call must enter proxy");
            check(manager.value(item) != service,
                    item.serviceName() + " legacy manager field must be replaced");

            hook.close();
            check(manager.value(item) == service,
                    item.serviceName() + " legacy manager rollback must restore original");
        }
    }

    private static void testStorageMountServiceAliasAndRollback() throws Exception {
        ServiceManager.sCache.clear();
        ServiceCase item = CASES.get(1);
        TestService service = new TestService(item.descriptor());
        TestBinder original = new TestBinder(item.descriptor(), service);
        service.binder = original;
        ServiceManager.sCache.put("mount", original);

        AutoCloseable hook = ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                new TestContext(null), "storage", "storage", item.descriptor(), identity(),
                List.of("storage", "mount"), item.fieldName());
        IBinder replacement = ServiceManager.getService("mount");
        check(replacement != original, "storage mount ServiceManager cache must be replaced");
        check(item.probe(replacement).equals("host-proxy"),
                "storage mount framework call must enter proxy");

        hook.close();
        check(ServiceManager.getService("mount") == original,
                "storage mount ServiceManager rollback must restore original");
    }

    private static void testInvalidDescriptorFailsClosed() {
        for (ServiceCase item : CASES) {
            ServiceManager.sCache.clear();
            TestService service = new TestService(item.descriptor());
            TestBinder wrong = new TestBinder("invalid." + item.serviceName(), service);
            ServiceManager.sCache.put(item.serviceName(), wrong);
            boolean rejected = false;
            try {
                ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                        new TestContext(null), item.serviceName(), item.serviceName(),
                        item.descriptor(), identity(), item.fieldName());
            } catch (IllegalStateException error) {
                rejected = hasSuppressedMessage(error, "Unexpected Binder descriptor");
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            check(rejected, item.serviceName() + " invalid descriptor must fail closed");
            check(ServiceManager.getService(item.serviceName()) == wrong,
                    item.serviceName() + " invalid descriptor must not mutate cache");
        }
    }

    private static void testUnsupportedBindingFailsClosed() {
        for (ServiceCase item : CASES) {
            ServiceManager.sCache.clear();
            UnsupportedManager manager = new UnsupportedManager(item.fieldName());
            Object original = manager.value(item);
            boolean rejected = false;
            try {
                ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                        new TestContext(manager), item.serviceName(), item.serviceName(),
                        item.descriptor(), identity(), item.fieldName());
            } catch (IllegalStateException error) {
                rejected = error.getMessage().contains("No supported Binder binding");
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            check(rejected, item.serviceName() + " unsupported structure must fail closed");
            check(manager.value(item) == original,
                    item.serviceName() + " unsupported structure must remain untouched");
        }
    }

    private static GuestIdentity identity() {
        return new GuestIdentity("guest.pkg", 12001, new ApplicationInfo(), Set.of(),
                "host.pkg", 10001);
    }

    private static boolean hasSuppressedMessage(Throwable error, String text) {
        for (Throwable candidate : error.getSuppressed()) {
            if (String.valueOf(candidate.getMessage()).contains(text)) return true;
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record ServiceCase(String serviceName, String descriptor, String fieldName) {
        String probe(IBinder binder) throws Exception {
            Class<?> stub = Class.forName(descriptor + "$Stub");
            Object service = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            if (service == null) throw new AssertionError(serviceName + " interface unavailable");
            if ("account".equals(serviceName)) {
                return Boolean.TRUE.equals(Class.forName(descriptor)
                        .getMethod("getAccountVisibility").invoke(service))
                        ? "host-proxy" : "unexpected";
            }
            return (String) Class.forName(descriptor).getMethod("probe").invoke(service);
        }
    }

    private static final class TestContext extends Context {
        private final Object service;

        TestContext(Object service) {
            this.service = service;
        }

        @Override public Object getSystemService(String name) {
            return service;
        }
    }

    private static final class TestService implements IAccountManager, IStorageManager {
        private final String descriptor;
        private IBinder binder;

        TestService(String descriptor) {
            this.descriptor = descriptor;
        }

        @Override public IBinder asBinder() {
            return binder;
        }

        @Override public boolean getAccountVisibility() {
            return false;
        }

        @Override public String probe() {
            return descriptor.equals("android.accounts.IAccountManager")
                    || descriptor.equals("android.os.storage.IStorageManager")
                    ? "host-proxy" : "unexpected";
        }
    }

    private static final class TestBinder implements IBinder {
        private final String descriptor;
        private final IInterface local;

        TestBinder(String descriptor, IInterface local) {
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

    private static final class LegacyManager {
        private IAccountManager mService;
        private IStorageManager mStorageManager;

        LegacyManager(ServiceCase item, TestService service) {
            if ("account".equals(item.serviceName())) mService = service;
            if ("storage".equals(item.serviceName())) mStorageManager = service;
        }

        IInterface value(ServiceCase item) {
            return "account".equals(item.serviceName()) ? mService : mStorageManager;
        }

        String probe(ServiceCase item) {
            return "account".equals(item.serviceName())
                    ? ((IAccountManager) value(item)).getAccountVisibility()
                        ? "host-proxy" : "unexpected"
                    : ((IStorageManager) value(item)).probe();
        }
    }

    private static final class UnsupportedManager {
        private Object mService;
        private Object mStorageManager;

        UnsupportedManager(String fieldName) {
            if ("mService".equals(fieldName)) mService = new Object();
            if ("mStorageManager".equals(fieldName)) mStorageManager = new Object();
        }

        Object value(ServiceCase item) {
            return "account".equals(item.serviceName()) ? mService : mStorageManager;
        }
    }
}
