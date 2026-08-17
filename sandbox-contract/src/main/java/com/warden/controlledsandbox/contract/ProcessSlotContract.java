package com.warden.controlledsandbox.contract;

/** Shared bounds for ordinary framework component process leases. */
public final class ProcessSlotContract {
    public static final int ORDINARY_SLOT_COUNT = 64;
    public static final int MAX_ORDINARY_SLOT = ORDINARY_SLOT_COUNT - 1;
    /** Dedicated Android isolated-Service workers kept outside the ordinary Guest pool. */
    public static final int ISOLATED_SLOT_COUNT = 16;
    private static final String ISOLATED_SERVICE_PREFIX =
            "com.warden.controlledsandbox.runtime.guest.IsolatedGuestProcessService";

    private ProcessSlotContract() { }

    public static boolean isOrdinarySlot(int slot) {
        return slot >= 0 && slot < ORDINARY_SLOT_COUNT;
    }

    public static boolean isIsolatedSlot(int slot) {
        return slot >= 0 && slot < ISOLATED_SLOT_COUNT;
    }

    public static String isolatedServiceClassName(int slot) {
        if (!isIsolatedSlot(slot)) {
            throw new IllegalArgumentException("invalid isolated process slot: " + slot);
        }
        return ISOLATED_SERVICE_PREFIX + slot;
    }
}
