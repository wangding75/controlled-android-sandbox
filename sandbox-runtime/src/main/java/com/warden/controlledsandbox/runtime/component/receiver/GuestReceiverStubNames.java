package com.warden.controlledsandbox.runtime.component.receiver;

import com.warden.controlledsandbox.contract.ProcessSlotContract;

/** Host manifest names used for real ActivityThread RECEIVER leases. */
public final class GuestReceiverStubNames {
    private static final String PREFIX =
            "com.warden.controlledsandbox.runtime.component.receiver.StubReceiverSlots$S";

    private GuestReceiverStubNames() { }

    public static String classNameFor(int slot) {
        if (!ProcessSlotContract.isOrdinarySlot(slot)) {
            throw new IllegalArgumentException("Invalid receiver slot: " + slot);
        }
        return PREFIX + slot;
    }
}
