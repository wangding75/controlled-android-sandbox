package com.warden.controlledsandbox.framework.identity;

import java.util.Objects;

/** Immutable identity mapping for exactly one Guest runtime process. */
public record IdentityContext(
        String guestPackage,
        int guestUid,
        String hostPackage,
        int hostUid,
        String guestProcess,
        int virtualUserId,
        long generation) {

    public IdentityContext {
        guestPackage = requirePackage(guestPackage, "guestPackage");
        hostPackage = requirePackage(hostPackage, "hostPackage");
        guestProcess = Objects.requireNonNull(guestProcess, "guestProcess").trim();
        if (guestProcess.isEmpty()) {
            throw new IllegalArgumentException("guestProcess must not be blank");
        }
        if (guestUid < 0 || hostUid < 0 || virtualUserId < 0 || generation < 1) {
            throw new IllegalArgumentException("UIDs and virtualUserId must be non-negative; generation must be positive");
        }
        if (guestPackage.equals(hostPackage)) {
            throw new IllegalArgumentException("guestPackage and hostPackage must differ");
        }
    }

    private static String requirePackage(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty() || normalized.indexOf('.') < 1 || normalized.endsWith(".")) {
            throw new IllegalArgumentException(name + " is not a valid package-like name: " + normalized);
        }
        return normalized;
    }
}
