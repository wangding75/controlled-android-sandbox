package com.warden.controlledsandbox.runtime.component.service;

/** Host-declared Service stubs used only as Android ActivityThread transport leases. */
public final class GuestServiceStubNames {
    private static final String PREFIX =
            "com.warden.controlledsandbox.runtime.component.service.StubService";

    private GuestServiceStubNames() { }

    public static String classNameFor(int slot) {
        if (slot < 0 || slot > 31) throw new IllegalArgumentException("Invalid service slot: " + slot);
        return PREFIX + slot;
    }
}
