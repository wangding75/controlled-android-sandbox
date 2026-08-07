package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualDetectionPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import java.util.List;

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
        require(!GuestClassLoader.isParentFirst(
                "com.warden.controlledsandbox.fixture.FixtureApplication"),
                "official Fixture is child first");
        GuestClassLoader loader = new GuestClassLoader("", "", null, GuestClassLoaderSelfTest.class.getClassLoader());
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
        System.out.println("PASS Guest class-loader host-boundary and detection policy self-test");
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
        try { done.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
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
        try { done.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
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
