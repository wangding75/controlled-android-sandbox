package com.warden.controlledsandbox.contract;

/** Shared bounds for ordinary framework component process leases. */
public final class ProcessSlotContract {
    public static final int ORDINARY_SLOT_COUNT = 64;
    public static final int MAX_ORDINARY_SLOT = ORDINARY_SLOT_COUNT - 1;

    private ProcessSlotContract() { }

    public static boolean isOrdinarySlot(int slot) {
        return slot >= 0 && slot < ORDINARY_SLOT_COUNT;
    }
}
