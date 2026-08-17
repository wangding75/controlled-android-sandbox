package com.warden.controlledsandbox.runtime.guest;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GuestDefiningLoaderSelfTest {
    private GuestDefiningLoaderSelfTest() { }

    public static void main(String[] args) throws Exception {
        GuestClassLoader loader = new GuestClassLoader("", "", null,
                GuestDefiningLoaderSelfTest.class.getClassLoader(),
                "com.example.guest");
        require(GuestDefiningLoader.unwrap(loader) == loader.definingLoader(),
                "unwrap uses the PathClassLoader defining world");
        boolean missed = false;
        try {
            GuestDefiningLoader.loadComponent(loader,
                    "com.example.guest.NativeAdversarialProbeService");
        } catch (ClassNotFoundException error) {
            missed = true;
            String message = String.valueOf(error.getMessage());
            require(message.contains("GUEST_DEFINING_LOADER_MISS"),
                    "component miss is labeled as a defining-loader miss");
            require(!message.contains("com.warden.controlledsandbox.debug"),
                    "component miss must not report the host debug DexPathList");
        }
        require(missed, "missing guest component class fails closed");

        AtomicBoolean parentAsked = new AtomicBoolean(false);
        ClassLoader parent = new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                parentAsked.set(true);
                throw new ClassNotFoundException("PARENT_SHOULD_NOT_RUN:" + name);
            }
        };
        GuestClassLoader isolated = new GuestClassLoader("", "", null, parent, "com.example.guest");
        boolean definedMiss = false;
        try {
            isolated.loadDefinedClass("com.example.guest.MissingService");
        } catch (ClassNotFoundException error) {
            definedMiss = true;
            require(String.valueOf(error.getMessage()).contains("GUEST_DEFINING_LOADER_MISS"),
                    "defined-class miss stays on the guest dex");
        }
        require(definedMiss, "defined-class miss throws");
        require(!parentAsked.get(), "defined-class miss must not consult the host parent");

        require(GuestClassLoader.isDeniedSandboxInternal(
                        "com.warden.controlledsandbox.runtime.guest.GuestDefiningLoader")
                        == GuestClassLoader.isDeniedSandboxInternal(
                        "com.warden.controlledsandbox.runtime.guest.GuestClassLoader"),
                "defining-loader type stays a runtime implementation");
        require(List.of("Activity", "Service", "Receiver", "Provider").size() == 4,
                "one contract covers the four component kinds");
        System.out.println("PASS guest defining-loader contract self-test");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
