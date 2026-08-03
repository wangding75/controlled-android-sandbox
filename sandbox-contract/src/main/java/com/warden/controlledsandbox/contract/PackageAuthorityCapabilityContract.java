package com.warden.controlledsandbox.contract;

import java.util.concurrent.atomic.AtomicLong;

/** Shared generation source and error codes for Package Service role capabilities. */
public final class PackageAuthorityCapabilityContract {
    public static final String MANAGEMENT_CAPABILITY_REQUIRED =
            "PACKAGE_MANAGEMENT_CAPABILITY_REQUIRED";
    public static final String RUNTIME_CAPABILITY_REQUIRED =
            "PACKAGE_RUNTIME_CAPABILITY_REQUIRED";

    private static final AtomicLong NEXT_GENERATION =
            new AtomicLong(Math.max(1L, System.currentTimeMillis()));

    private PackageAuthorityCapabilityContract() { }

    public static long nextGeneration() {
        while (true) {
            long current = NEXT_GENERATION.get();
            long wallClock = Math.max(1L, System.currentTimeMillis());
            long next = Math.max(current + 1L, wallClock);
            if (NEXT_GENERATION.compareAndSet(current, next)) return next;
        }
    }
}
