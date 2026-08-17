package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualDetectionPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import java.net.URL;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Enumeration;
import java.util.Collections;

public final class GuestClassLoaderSelfTest {
    public static void main(String[] args) {
        require(GuestClassLoader.isParentFirst("java.lang.String"), "Java parent first");
        require(GuestClassLoader.isParentFirst("android.app.Activity"), "Android parent first");
        require(GuestClassLoader.isParentFirst("com.warden.controlledsandbox.contract.IRuntimeBroker"),
                "sandbox contract parent first");
        require(GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.runtime.guest.GuestRuntimeEnvironment"),
                "runtime implementation denied");
        require(GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.framework.core.FrameworkHooks"),
                "framework implementation denied");
        require(GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.nativebridge.NativePolicy"),
                "native management implementation denied");
        require(GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.domain.SandboxIdentity"),
                "domain implementation denied");
        require(GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.companion32.CompanionRuntime"),
                "companion implementation denied");
        require(GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.PackageManagementService"),
                "Host root-package implementation denied");
        require(!GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.contract.IRuntimeBroker"),
                "contract remains available");
        require(!GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.fixture.FixtureApplication"),
                "official 64-bit Fixture remains Guest-loadable");
        require(!GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.fixture.MainActivity"),
                "official Fixture Activity remains Guest-loadable");
        require(!GuestClassLoader.isDeniedSandboxInternal(
                "com.warden.controlledsandbox.fixture32.MainActivity"),
                "32-bit Fixture namespace remains Guest-loadable");
        require(GuestClassLoader.isPrivilegedContract(
                "com.warden.controlledsandbox.contract.IPackageAuthorityBootstrap"),
                "private Package Authority bootstrap contract denied");
        require(GuestClassLoader.isPrivilegedContract(
                "com.warden.controlledsandbox.contract.IPackageService"),
                "privileged Package Service contract denied");
        require(GuestClassLoader.isPrivilegedContract(
                "com.warden.controlledsandbox.contract.internal.InternalCapability"),
                "internal contract namespace denied");
        require(!GuestClassLoader.isPrivilegedContract(
                "com.warden.controlledsandbox.contract.IRuntimeBroker"),
                "Guest-safe runtime contract remains available");
        require(!GuestClassLoader.isParentFirst("com.example.guest.MainActivity"), "Guest child first");
        require(!GuestClassLoader.isParentFirst("org.example.library.Client"), "Guest libraries child first");
        require(!GuestClassLoader.isParentFirst("kotlin.jvm.internal.Intrinsics"),
                "Guest Kotlin runtime child first");
        require(!GuestClassLoader.isParentFirst(
                "com.warden.controlledsandbox.fixture.FixtureApplication"),
                "official Fixture is child first");
        GuestClassLoader loader = new GuestClassLoader("", "", null, GuestClassLoaderSelfTest.class.getClassLoader());
        require(loader.definingLoader() instanceof dalvik.system.PathClassLoader,
                "defining loader is platform PathClassLoader");
        require(loader.definingLoader().getClass() == dalvik.system.PathClassLoader.class,
                "defining loader type is exactly PathClassLoader");
        require(loader.definingLoader() != loader, "policy wrapper is not the defining loader");
        loader.configureDetection(new VirtualDetectionPolicySnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, true, true, true, true, true, 2,
                List.of(), List.of("com.example.hidden", "org.example.internal"), List.of()));
        boolean hidden = false;
        try { loader.loadClass("com.example.hidden.DetectionBridge"); }
        catch (ClassNotFoundException expected) { hidden = true; }
        require(hidden && loader.suspiciousQueryCount() == 1, "policy-hidden class query");
        hidden = false;
        try { loader.loadClass("org.example.internal.RuntimeBridge"); }
        catch (ClassNotFoundException expected) { hidden = true; }
        require(hidden && loader.suspiciousQueryCount() == 2, "second policy-hidden class query");
        GuestClassLoader fixtureLoader = new GuestClassLoader("", "", null,
                GuestClassLoaderSelfTest.class.getClassLoader(),
                "com.warden.controlledsandbox.fixture");
        fixtureLoader.configureDetection(new VirtualDetectionPolicySnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, true, true, true, true, true, 2,
                List.of(), List.of("com.warden.controlledsandbox"), List.of()));
        try {
            fixtureLoader.loadClass("com.warden.controlledsandbox.fixture.FixtureApplication");
        } catch (ClassNotFoundException unexpected) {
            throw new AssertionError("active Guest package must remain loadable", unexpected);
        }
        require(fixtureLoader.suspiciousQueryCount() == 0,
                "active Guest package is exempt from Host namespace hiding");
        GuestClassLoader compatibilityFixtureLoader = new GuestClassLoader("", "", null,
                GuestClassLoaderSelfTest.class.getClassLoader(),
                "com.warden.controlledsandbox.fixture32",
                List.of("com.warden.controlledsandbox.fixture.FixtureApplication",
                        "com.warden.controlledsandbox.fixture.MainActivity"));
        compatibilityFixtureLoader.configureDetection(new VirtualDetectionPolicySnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, true, true, true, true, true, 2,
                List.of(), List.of("com.warden.controlledsandbox"), List.of()));
        try {
            compatibilityFixtureLoader.loadClass(
                    "com.warden.controlledsandbox.fixture.FixtureApplication");
        } catch (ClassNotFoundException unexpected) {
            throw new AssertionError("declared Guest namespace must remain loadable", unexpected);
        }
        require(compatibilityFixtureLoader.suspiciousQueryCount() == 0,
                "manifest-declared compatibility namespace is exempt without global whitelist");
        boolean undeclaredHidden = false;
        try {
            compatibilityFixtureLoader.loadClass(
                    "com.warden.controlledsandbox.other.UnlistedDetectionClass");
        } catch (ClassNotFoundException expected) { undeclaredHidden = true; }
        require(undeclaredHidden && compatibilityFixtureLoader.suspiciousQueryCount() == 1,
                "undeclared compatibility namespace class remains policy-hidden");
        loader.configureDetection(new VirtualDetectionPolicySnapshot(
                VirtualLocationProfileSnapshot.MODE_HOST, false, false, false, false, false, 0,
                List.of(), List.of(), List.of()));
        require(loader.suspiciousQueryCount() == 0, "HOST mode resets detection ledger");
        try {
            concurrentLoadSameClass(loader);
            concurrentLoadDifferentClasses(loader);
            repeatedLoad(loader);
            verifyNoParallelOrLockStriping(loader);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        loadNonExistentThrows(loader);
        verifyGuestFirstResourceLookup();
        System.out.println("PASS Guest class-loader host-boundary and detection policy self-test");
    }

    private static void verifyGuestFirstResourceLookup() {
        URL expected = GuestClassLoaderSelfTest.class.getResource("GuestClassLoaderSelfTest.class");
        require(expected != null, "self-test class resource exists");
        ClassLoader guestResources = new ClassLoader(null) {
            @Override protected URL findResource(String name) {
                return "guest-only-resource".equals(name) ? expected : null;
            }

            @Override protected Enumeration<URL> findResources(String name) {
                return "guest-only-resource".equals(name)
                        ? Collections.enumeration(List.of(expected))
                        : Collections.emptyEnumeration();
            }
        };
        try {
            Constructor<GuestClassLoader> constructor = GuestClassLoader.class.getDeclaredConstructor(
                    ClassLoader.class, ClassLoader.class, String.class, List.class, List.class);
            constructor.setAccessible(true);
            GuestClassLoader loader = constructor.newInstance(guestResources,
                    GuestClassLoaderSelfTest.class.getClassLoader(), "", List.of(), List.of());
            require(expected.equals(loader.getResource("guest-only-resource")),
                    "Guest resource wins over parent");
            Enumeration<URL> resources;
            try {
                resources = loader.getResources("guest-only-resource");
            } catch (java.io.IOException error) {
                throw new AssertionError("Guest resource enumeration failed", error);
            }
            require(resources.hasMoreElements() && expected.equals(resources.nextElement()),
                    "Guest resource enumeration is visible");
            require(!resources.hasMoreElements(), "duplicate Guest resource is removed");
            try (InputStream stream = loader.getResourceAsStream("guest-only-resource")) {
                require(stream != null && stream.read() >= 0,
                        "Guest resource stream is visible");
            } catch (java.io.IOException error) {
                throw new AssertionError("Guest resource stream failed", error);
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Guest resource loader construction failed", error);
        }
    }

    private static void verifyNoParallelOrLockStriping(GuestClassLoader loader) {
        for (java.lang.reflect.Field field : GuestClassLoader.class.getDeclaredFields()) {
            require(!field.getName().equals("locks"), "GuestClassLoader must not contain locks array");
        }
    }

    private static void concurrentLoadSameClass(GuestClassLoader loader) {
        int threadCount = 10;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger(0);
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    latch.await();
                    Class<?> clazz = loader.loadClass("java.lang.String");
                    if (clazz == String.class) {
                        success.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        latch.countDown();
        try {
            boolean completed = done.await(10, java.util.concurrent.TimeUnit.SECONDS);
            require(completed, "concurrent load same class timed out");
        } catch (InterruptedException e) { throw new RuntimeException(e); }
        require(success.get() == threadCount, "concurrent load same class success");
    }

    private static void concurrentLoadDifferentClasses(GuestClassLoader loader) {
        int threadCount = 10;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger(0);
        String[] classNames = {
            "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
            "java.lang.Short", "java.lang.Byte", "java.lang.Character", "java.lang.Boolean",
            "java.lang.Object", "java.lang.Class"
        };
        for (int i = 0; i < threadCount; i++) {
            final String className = classNames[i];
            new Thread(() -> {
                try {
                    latch.await();
                    Class<?> clazz = loader.loadClass(className);
                    if (clazz != null) {
                        success.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        latch.countDown();
        try {
            boolean completed = done.await(10, java.util.concurrent.TimeUnit.SECONDS);
            require(completed, "concurrent load different classes timed out");
        } catch (InterruptedException e) { throw new RuntimeException(e); }
        require(success.get() == threadCount, "concurrent load different classes success");
    }

    private static void repeatedLoad(GuestClassLoader loader) throws Exception {
        Class<?> first = loader.loadClass("java.lang.String");
        Class<?> second = loader.loadClass("java.lang.String");
        require(first == second, "repeated load returns same class");
    }

    private static void loadNonExistentThrows(GuestClassLoader loader) {
        boolean thrown = false;
        try {
            loader.loadClass("com.example.NonExistentClass");
        } catch (ClassNotFoundException e) {
            thrown = true;
        }
        require(thrown, "non-existent class throws ClassNotFoundException");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
