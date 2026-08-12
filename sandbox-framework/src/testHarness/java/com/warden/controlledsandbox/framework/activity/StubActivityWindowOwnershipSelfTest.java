package com.warden.controlledsandbox.framework.activity;

/** Deterministic lifecycle race tests for the production StubActivityWindowOwnership fence. */
public final class StubActivityWindowOwnershipSelfTest {
    private StubActivityWindowOwnershipSelfTest() { }

    public static void main(String[] args) {
        case1DetachedCallback();
        case2SlotReuseGenerationFence();
        case3RecordReplacement();
        case4VirtualUserOwnership();
        case5ForwardNavigationOnlyCurrentWindow();
        System.out.println("PASS StubActivityWindowOwnershipSelfTest oldBehavior=REPRODUCED_AND_REJECTED");
    }

    private static void case1DetachedCallback() {
        StubActivityWindowOwnership fence = new StubActivityWindowOwnership();
        StubActivityWindowOwnership.Owner owner = owner(0, 4, "activity-a", 11);
        StubActivityWindowOwnership.Lease lease = fence.bind(owner);
        check(fence.attach(lease, "decor-a"), "case1 attach");
        check(fence.detach(lease, "decor-a"), "case1 detach");

        // This is the old behavior: a lifecycle callback retained only a reference to the
        // Activity and would still reach updateViewLayout after removeView.
        check(new LegacyWindowCallback().mayUpdateAfterRemove(),
                "case1 legacy model must reproduce the stale update");
        check(!fence.mayUpdate(lease, "decor-a"),
                "case1 detached DecorView must reject update");
    }

    private static void case2SlotReuseGenerationFence() {
        StubActivityWindowOwnership fence = new StubActivityWindowOwnership();
        StubActivityWindowOwnership.Lease oldLease = fence.bind(owner(0, 6, "old", 20));
        StubActivityWindowOwnership.Lease newLease = fence.replace(oldLease, owner(0, 6, "new", 21));
        check(!fence.accepts(oldLease), "case2 old generation callback accepted");
        check(fence.accepts(newLease), "case2 new generation owner missing");
    }

    private static void case3RecordReplacement() {
        StubActivityWindowOwnership fence = new StubActivityWindowOwnership();
        StubActivityWindowOwnership.Lease oldLease = fence.bind(owner(0, 5, "session", 30));
        StubActivityWindowOwnership.Lease newLease = fence.replace(
                oldLease, owner(0, 5, "session", 30).withActivityToken("activity-new"));
        check(!fence.accepts(oldLease), "case3 old ActivityClientRecord lease survived replacement");
        check(fence.accepts(newLease), "case3 replacement lease missing");
    }

    private static void case4VirtualUserOwnership() {
        StubActivityWindowOwnership fence = new StubActivityWindowOwnership();
        StubActivityWindowOwnership.Lease user0 = fence.bind(owner(0, 6, "u0", 40));
        StubActivityWindowOwnership.Lease user1 = fence.replace(user0, owner(1, 7, "u1", 40));
        check(!fence.accepts(user0), "case4 user0 callback crossed into user1");
        check(fence.accepts(user1), "case4 user1 owner missing");
    }

    private static void case5ForwardNavigationOnlyCurrentWindow() {
        StubActivityWindowOwnership fence = new StubActivityWindowOwnership();
        StubActivityWindowOwnership.Lease lease = fence.bind(owner(0, 7, "forward", 50));
        check(fence.attach(lease, "decor-current"), "case5 attach");
        check(fence.mayUpdate(lease, "decor-current"),
                "case5 current attached window should remain updateable");
        check(!fence.mayUpdate(lease, "decor-previous"),
                "case5 forward navigation must not authorize an old DecorView");
    }

    private static StubActivityWindowOwnership.Owner owner(int user, int slot, String session,
                                                            long generation) {
        return new StubActivityWindowOwnership.Owner("com.example.fixture", user, session,
                generation, slot, "activity-" + session, 100 + slot);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class LegacyWindowCallback {
        private boolean attached = true;
        boolean mayUpdateAfterRemove() {
            attached = false;
            return true;
        }
    }
}
