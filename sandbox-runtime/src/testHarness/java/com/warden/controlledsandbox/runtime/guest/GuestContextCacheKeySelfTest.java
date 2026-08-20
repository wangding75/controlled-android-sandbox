package com.warden.controlledsandbox.runtime.guest;

/** Ensures peer package contexts cannot reuse a ClassLoader across revisions or code modes. */
public final class GuestContextCacheKeySelfTest {
    public static void main(String[] args) {
        String base = GuestContext.packageContextCacheKey("peer.pkg", "revision-a", 0);
        String upgraded = GuestContext.packageContextCacheKey("peer.pkg", "revision-b", 0);
        String code = GuestContext.packageContextCacheKey("peer.pkg", "revision-a", 1);
        require(!base.equals(upgraded), "package context key includes package revision");
        require(!base.equals(code), "package context key separates code/resource modes");
        require(base.equals(GuestContext.packageContextCacheKey("peer.pkg", "revision-a", 0)),
                "package context key is deterministic");
        System.out.println("PASS Guest package-context revision cache-key self-test");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
