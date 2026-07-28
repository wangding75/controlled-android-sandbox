package com.warden.controlledsandbox.framework.routing;

import java.util.concurrent.atomic.AtomicInteger;

public final class VirtualPendingIntentRegistrySelfTest {
    public static void main(String[] args) throws Exception {
        AtomicInteger deliveries = new AtomicInteger();
        VirtualPendingIntentRegistry registry = new VirtualPendingIntentRegistry(
                "guest.pkg", 2, 7L, (record, fillIn) -> deliveries.incrementAndGet());
        Object firstToken = new Object();
        VirtualPendingIntentRegistry.Spec base = new VirtualPendingIntentRegistry.Spec(
                VirtualPendingIntentRegistry.Kind.BROADCAST, 8, "guest.ACTION", "", "", 0);
        VirtualPendingIntentRegistry.IssueResult first = registry.issue(base, firstToken, "payload-1");
        require(first.created(), "first issue created");
        Object duplicateToken = new Object();
        VirtualPendingIntentRegistry.IssueResult duplicate = registry.issue(base, duplicateToken, "payload-2");
        require(!duplicate.created() && duplicate.record().token() == firstToken,
                "equivalent sender reuses stable token");
        registry.send(firstToken, null);
        require(deliveries.get() == 1 && first.record().sends() == 1, "delivery tracked");

        VirtualPendingIntentRegistry.Spec updated = new VirtualPendingIntentRegistry.Spec(
                VirtualPendingIntentRegistry.Kind.BROADCAST, 8, "guest.ACTION", "", "",
                VirtualPendingIntentRegistry.FLAG_UPDATE_CURRENT);
        registry.issue(updated, duplicateToken, "payload-new");
        require("payload-new".equals(first.record().payload()), "update current replaces payload");

        Object oneShotToken = new Object();
        VirtualPendingIntentRegistry.Spec oneShot = new VirtualPendingIntentRegistry.Spec(
                VirtualPendingIntentRegistry.Kind.SERVICE, 9, "guest.SERVICE", "", "",
                VirtualPendingIntentRegistry.FLAG_ONE_SHOT | VirtualPendingIntentRegistry.FLAG_IMMUTABLE);
        registry.issue(oneShot, oneShotToken, "service");
        boolean immutable = false;
        try { registry.send(oneShotToken, "fill"); }
        catch (SecurityException expected) { immutable = true; }
        require(immutable, "immutable sender rejects fill-in payload");
        registry.send(oneShotToken, null);
        require(registry.find(oneShotToken) == null, "one-shot sender cancelled after send");

        Object cancelled = new Object();
        registry.issue(new VirtualPendingIntentRegistry.Spec(VirtualPendingIntentRegistry.Kind.ACTIVITY,
                10, "guest.ACTIVITY", "GuestActivity", "", 0), cancelled, "activity");
        require(registry.cancel(cancelled), "explicit cancellation");
        boolean rejected = false;
        try { registry.send(cancelled, null); } catch (IllegalStateException expected) { rejected = true; }
        require(rejected, "cancelled sender cannot send");
        registry.close();
        require(registry.snapshot().active() == 0, "registry close clears all senders");
        System.out.println("PASS virtual PendingIntent identity and lifecycle self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
