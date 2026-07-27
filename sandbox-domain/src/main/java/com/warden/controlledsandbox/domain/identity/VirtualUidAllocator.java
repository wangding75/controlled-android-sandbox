package com.warden.controlledsandbox.domain.identity;

/** Android-style virtual UID composition and decomposition. */
public final class VirtualUidAllocator {
    public static final int PER_USER_RANGE = 100_000;
    public static final int FIRST_APPLICATION_UID = 10_000;
    public static final int LAST_APPLICATION_UID = 99_999;

    public int compose(int appId, int virtualUserId) {
        if (appId < FIRST_APPLICATION_UID || appId > LAST_APPLICATION_UID) {
            throw new IllegalArgumentException("appId out of application range");
        }
        if (virtualUserId < 0 || virtualUserId > 999) {
            throw new IllegalArgumentException("virtualUserId out of range");
        }
        return Math.addExact(Math.multiplyExact(virtualUserId, PER_USER_RANGE), appId);
    }

    public int userId(int virtualUid) { return Math.floorDiv(virtualUid, PER_USER_RANGE); }
    public int appId(int virtualUid) { return Math.floorMod(virtualUid, PER_USER_RANGE); }
}
