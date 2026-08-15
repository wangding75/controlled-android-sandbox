package com.warden.controlledsandbox.runtime.component.service;

import com.warden.controlledsandbox.contract.ProcessSlotContract;

/** Host-declared Service stubs used only as Android ActivityThread transport leases. */
public final class GuestServiceStubNames {
    private static final String PREFIX =
            "com.warden.controlledsandbox.runtime.component.service.StubService";

    private GuestServiceStubNames() { }

    public static String classNameFor(int slot) {
        if (!ProcessSlotContract.isOrdinarySlot(slot)) {
            throw new IllegalArgumentException("Invalid service slot: " + slot);
        }
        return PREFIX + slot;
    }
}
