package com.warden.controlledsandbox.runtime.component.activity;

/** Deterministic proof that the physical Activity identity pool never wraps or aliases. */
public final class PhysicalActivityIdentityAllocatorSelfTest {
    public static void main(String[] args) {
        PhysicalActivityIdentityAllocator allocator = new PhysicalActivityIdentityAllocator(16);
        for (int index = 0; index < 16; index++) {
            int window = allocator.allocate(7, "activity-" + index);
            check(window == index, "allocator must choose the first deterministic free window");
        }
        check(allocator.liveCount() == 16, "sixteen live identities must be retained");
        check(!allocator.hasCollision(), "live physical identities must be unique");
        expectExhausted(() -> allocator.allocate(7, "activity-16"));
        check(allocator.windowFor("activity-0") == 0, "exhaustion must not overwrite activity-0");
        check(allocator.windowFor("activity-15") == 15, "exhaustion must not overwrite activity-15");

        allocator.release("activity-3");
        check(allocator.allocate(7, "activity-reused") == 3,
                "a released identity may be reused deterministically");
        allocator.rebind("activity-4", "activity-rebound");
        check(allocator.windowFor("activity-rebound") == 4, "mapping must be recoverable");
        check(allocator.windowFor("activity-4") == null, "old token must not remain aliased");
        check(!allocator.hasCollision(), "release/rebind must preserve uniqueness");
        System.out.println("PASS bounded physical Activity identity allocator");
    }

    private static void expectExhausted(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("expected physical identity pool exhaustion");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().startsWith("PHYSICAL_ACTIVITY_IDENTITY_POOL_EXHAUSTED"),
                    "pool exhaustion must fail with a deterministic error");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
