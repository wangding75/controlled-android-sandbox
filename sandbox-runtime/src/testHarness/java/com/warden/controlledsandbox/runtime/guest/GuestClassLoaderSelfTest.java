package com.warden.controlledsandbox.runtime.guest;

public final class GuestClassLoaderSelfTest {
    public static void main(String[] args) {
        require(GuestClassLoader.isParentFirst("java.lang.String"), "Java parent first");
        require(GuestClassLoader.isParentFirst("android.app.Activity"), "Android parent first");
        require(GuestClassLoader.isParentFirst("com.warden.controlledsandbox.contract.IRuntimeBroker"),
                "sandbox contract parent first");
        require(!GuestClassLoader.isParentFirst("com.example.guest.MainActivity"), "Guest child first");
        require(!GuestClassLoader.isParentFirst("org.example.library.Client"), "Guest libraries child first");
        System.out.println("PASS Guest class-loader policy self-test");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
