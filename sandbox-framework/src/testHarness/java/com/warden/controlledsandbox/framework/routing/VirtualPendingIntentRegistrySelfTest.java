package com.warden.controlledsandbox.framework.routing;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class VirtualPendingIntentRegistrySelfTest {
    public static void main(String[] args) throws Exception {
        localLifecycle();
        durableLifecycle();
        oneShotAtomicity();
        boundedLocalRegistry();
        System.out.println("PASS virtual PendingIntent identity and lifecycle self-test");
    }

    private static void localLifecycle() throws Exception {
        AtomicInteger deliveries = new AtomicInteger();
        VirtualPendingIntentRegistry registry = new VirtualPendingIntentRegistry(
                "guest.pkg", 2, 7L, (record, request) -> deliveries.incrementAndGet());
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
        require(registry.snapshot().active() == 0, "registry close clears all local handles");
    }

    private static void durableLifecycle() throws Exception {
        MemoryPersistence shared = new MemoryPersistence();
        AtomicReference<VirtualPendingIntentRegistry.SendRequest> observed = new AtomicReference<>();
        VirtualPendingIntentRegistry firstProcess = new VirtualPendingIntentRegistry(
                "guest.pkg", 2, 12002, 11L, "guest.pkg", "rev-a", shared,
                (record, request) -> { observed.set(request); return 77; });
        Object tokenOne = new Object();
        VirtualPendingIntentRegistry.Spec mutable = new VirtualPendingIntentRegistry.Spec(
                VirtualPendingIntentRegistry.Kind.ACTIVITY_RESULT, 41, "guest.RESULT", "activity-7", "",
                VirtualPendingIntentRegistry.FLAG_MUTABLE, "guest.permission.SEND_RESULT");
        VirtualPendingIntentRegistry.IssueResult issued = firstProcess.issue(mutable, tokenOne, "base");
        require(issued.created() && issued.record().creatorUid() == 12002,
                "creator identity is virtual and durable");
        String persistentId = issued.record().persistentTokenId();
        int result = firstProcess.send(tokenOne, new VirtualPendingIntentRegistry.SendRequest(
                "fill", 0x30, 0x20, "guest.permission.SEND_RESULT", 13000, 41));
        require(result == 77 && "fill".equals(observed.get().fillInPayload())
                        && observed.get().resultCode() == 41,
                "mutable sender accepts bounded fill-in request");
        require(issued.record().sends() == 1, "durable send count committed");
        firstProcess.close();
        require(shared.records().size() == 1,
                "process shutdown retains persistent sender token");

        AtomicReference<Object> recoveredPayload = new AtomicReference<>();
        VirtualPendingIntentRegistry alarmRecoveryProcess = new VirtualPendingIntentRegistry(
                "guest.pkg", 2, 12002, 12L, "guest.pkg", "rev-a", shared,
                (record, request) -> { recoveredPayload.set(record.payload()); return 79; });
        int recoveredDelivery = alarmRecoveryProcess.sendPersistent(persistentId,
                new VirtualPendingIntentRegistry.SendRequest(null, 0, 0,
                        "guest.permission.SEND_RESULT", -1));
        require(recoveredDelivery == 79 && "base".equals(recoveredPayload.get()),
                "durable sender dispatches by persistent token without Binder reissue");
        require(alarmRecoveryProcess.snapshot().active() == 1,
                "persistent delivery materializes one current-process handle");
        alarmRecoveryProcess.close();

        VirtualPendingIntentRegistry secondProcess = new VirtualPendingIntentRegistry(
                "guest.pkg", 2, 12002, 13L, "guest.pkg", "rev-a", shared,
                (record, request) -> 78);
        Object tokenTwo = new Object();
        VirtualPendingIntentRegistry.IssueResult recovered = secondProcess.issue(mutable, tokenTwo, "ignored");
        require(!recovered.created() && persistentId.equals(recovered.record().persistentTokenId()),
                "equivalent sender rebinds persistent token after process restart");
        require(recovered.record().generation() == 13L && recovered.record().sends() == 2,
                "recovered sender binds current generation and send count");

        boolean permissionDenied = false;
        try {
            secondProcess.send(tokenTwo, new VirtualPendingIntentRegistry.SendRequest(
                    null, 0, 0, "guest.permission.WRONG", 13000));
        } catch (SecurityException expected) { permissionDenied = true; }
        require(permissionDenied, "sender permission enforced before delivery");

        Object immutableToken = new Object();
        VirtualPendingIntentRegistry.Spec immutable = new VirtualPendingIntentRegistry.Spec(
                VirtualPendingIntentRegistry.Kind.BROADCAST, 55, "guest.IMMUTABLE", "", "",
                VirtualPendingIntentRegistry.FLAG_IMMUTABLE);
        secondProcess.issue(immutable, immutableToken, "base");
        boolean flagsDenied = false;
        try {
            secondProcess.send(immutableToken,
                    new VirtualPendingIntentRegistry.SendRequest(null, 1, 1, "", -1));
        } catch (SecurityException expected) { flagsDenied = true; }
        require(flagsDenied, "immutable sender rejects flag mutation");

        Object noCreate = new Object();
        VirtualPendingIntentRegistry.IssueResult noCreateResult = secondProcess.issue(
                new VirtualPendingIntentRegistry.Spec(VirtualPendingIntentRegistry.Kind.SERVICE,
                        99, "guest.MISSING", "", "", VirtualPendingIntentRegistry.FLAG_NO_CREATE),
                noCreate, null);
        require(noCreateResult.record() == null, "NO_CREATE does not allocate a sender");

        boolean conflictingMutability = false;
        try {
            new VirtualPendingIntentRegistry.Spec(VirtualPendingIntentRegistry.Kind.ACTIVITY, 1,
                    "guest.INVALID", "", "", VirtualPendingIntentRegistry.FLAG_IMMUTABLE
                            | VirtualPendingIntentRegistry.FLAG_MUTABLE);
        } catch (IllegalArgumentException expected) { conflictingMutability = true; }
        require(conflictingMutability, "conflicting mutable and immutable flags rejected");

        secondProcess.close();
        VirtualPendingIntentRegistry newRevision = new VirtualPendingIntentRegistry(
                "guest.pkg", 2, 12002, 14L, "guest.pkg", "rev-b", shared,
                (record, request) -> 0);
        Object replacement = new Object();
        VirtualPendingIntentRegistry.IssueResult replaced = newRevision.issue(mutable, replacement, "rev-b");
        require(replaced.created() && !persistentId.equals(replaced.record().persistentTokenId()),
                "APK revision change invalidates old persistent sender");
        require(shared.records().stream()
                        .allMatch(value -> "rev-b".equals(value.packageRevision())),
                "old revision senders are removed from authority state");
        newRevision.cancelAll();
        require(shared.records().isEmpty(), "cancelAll removes durable senders");
        newRevision.close();
    }

    private static void boundedLocalRegistry() {
        VirtualPendingIntentRegistry registry = new VirtualPendingIntentRegistry(
                "bounded.pkg", 0, 1L, (record, request) -> 0);
        for (int index = 0; index < VirtualPendingIntentRegistry.MAX_ACTIVE_RECORDS; index++) {
            registry.issue(new VirtualPendingIntentRegistry.Spec(
                    VirtualPendingIntentRegistry.Kind.BROADCAST, index,
                    "bounded.ACTION." + index, "", "", 0), new Object(), null);
        }
        boolean bounded = false;
        try {
            registry.issue(new VirtualPendingIntentRegistry.Spec(
                    VirtualPendingIntentRegistry.Kind.BROADCAST, 99999,
                    "bounded.OVERFLOW", "", "", 0), new Object(), null);
        } catch (IllegalStateException expected) { bounded = true; }
        require(bounded, "local PendingIntent registry capacity");
        registry.close();
    }

    private static void oneShotAtomicity() throws Exception {
        MemoryPersistence shared = new MemoryPersistence();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        VirtualPendingIntentRegistry.Spec spec = new VirtualPendingIntentRegistry.Spec(
                VirtualPendingIntentRegistry.Kind.BROADCAST, 17, "guest.ONE_SHOT", "", "",
                VirtualPendingIntentRegistry.FLAG_ONE_SHOT);
        VirtualPendingIntentRegistry first = new VirtualPendingIntentRegistry(
                "guest.pkg", 2, 12002, 21L, "guest.pkg", "rev-a", shared,
                (record, request) -> {
                    entered.countDown();
                    release.await();
                    deliveries.incrementAndGet();
                    return 0;
                });
        Object firstToken = new Object();
        first.issue(spec, firstToken, null);
        VirtualPendingIntentRegistry second = new VirtualPendingIntentRegistry(
                "guest.pkg", 2, 12002, 22L, "guest.pkg", "rev-a", shared,
                (record, request) -> { deliveries.incrementAndGet(); return 0; });
        Object secondToken = new Object();
        second.issue(spec, secondToken, null);
        Thread sender = new Thread(() -> {
            try { first.send(firstToken, null); }
            catch (Throwable error) { failure.set(error); }
        }, "pending-intent-one-shot-test");
        sender.start();
        require(entered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "one-shot first send did not enter delivery");
        boolean rejected = false;
        try { second.send(secondToken, null); }
        catch (IllegalStateException expected) { rejected = true; }
        release.countDown();
        sender.join(5000L);
        require(rejected && !sender.isAlive() && failure.get() == null && deliveries.get() == 1,
                "one-shot durable token is claimed before concurrent delivery");
        first.close();
        second.close();
    }

    private static final class MemoryPersistence implements VirtualPendingIntentRegistry.Persistence {
        private final java.util.Map<String, VirtualPendingIntentRegistry.DurableRecord> records =
                new java.util.LinkedHashMap<>();
        private long next = 1L;

        @Override public synchronized VirtualPendingIntentRegistry.DurableRecord reserve(
                VirtualPendingIntentRegistry.DurableRecord candidate, boolean noCreate,
                boolean cancelCurrent, boolean updateCurrent) {
            records.values().removeIf(value -> !value.packageRevision().equals(candidate.packageRevision()));
            VirtualPendingIntentRegistry.DurableRecord existing = records.values().stream()
                    .filter(value -> !value.cancelled()
                            && value.kind().equals(candidate.kind())
                            && value.requestCode() == candidate.requestCode()
                            && value.filterIdentity().equals(candidate.filterIdentity()))
                    .findFirst().orElse(null);
            if (noCreate) return existing == null ? null : rebind(existing, candidate);
            if (existing != null && cancelCurrent) {
                records.remove(existing.tokenId()); existing = null;
            }
            if (existing != null) {
                VirtualPendingIntentRegistry.DurableRecord value = updateCurrent
                        ? new VirtualPendingIntentRegistry.DurableRecord(existing.tokenId(), candidate.kind(),
                                candidate.requestCode(), candidate.action(), candidate.component(),
                                candidate.data(), candidate.filterIdentity(), candidate.flags(),
                                candidate.creatorPackage(), candidate.creatorUid(), candidate.requiredPermission(),
                                candidate.ownerProcessName(), candidate.ownerGeneration(), candidate.packageRevision(),
                                candidate.payload(), existing.sends(), false, System.currentTimeMillis())
                        : rebind(existing, candidate);
                records.put(value.tokenId(), value); return value;
            }
            VirtualPendingIntentRegistry.DurableRecord created = new VirtualPendingIntentRegistry.DurableRecord(
                    "memory-pi-" + next++, candidate.kind(), candidate.requestCode(), candidate.action(),
                    candidate.component(), candidate.data(), candidate.filterIdentity(), candidate.flags(),
                    candidate.creatorPackage(), candidate.creatorUid(), candidate.requiredPermission(),
                    candidate.ownerProcessName(), candidate.ownerGeneration(), candidate.packageRevision(),
                    candidate.payload(), 0, false, System.currentTimeMillis());
            records.put(created.tokenId(), created); return created;
        }

        @Override public synchronized VirtualPendingIntentRegistry.DurableRecord markSent(String tokenId) {
            VirtualPendingIntentRegistry.DurableRecord value = records.get(tokenId);
            if (value == null) throw new IllegalStateException("VIRTUAL_PENDING_INTENT_CANCELLED");
            boolean cancelled = (value.flags() & VirtualPendingIntentRegistry.FLAG_ONE_SHOT) != 0;
            VirtualPendingIntentRegistry.DurableRecord updated = new VirtualPendingIntentRegistry.DurableRecord(
                    value.tokenId(), value.kind(), value.requestCode(), value.action(), value.component(),
                    value.data(), value.filterIdentity(), value.flags(), value.creatorPackage(), value.creatorUid(),
                    value.requiredPermission(), value.ownerProcessName(), value.ownerGeneration(),
                    value.packageRevision(), value.payload(), value.sends() + 1, cancelled,
                    System.currentTimeMillis());
            if (cancelled) records.remove(tokenId); else records.put(tokenId, updated);
            return updated;
        }

        @Override public synchronized boolean cancel(String tokenId) { return records.remove(tokenId) != null; }
        @Override public synchronized java.util.List<VirtualPendingIntentRegistry.DurableRecord> records() {
            return java.util.List.copyOf(records.values());
        }
        private static VirtualPendingIntentRegistry.DurableRecord rebind(
                VirtualPendingIntentRegistry.DurableRecord value,
                VirtualPendingIntentRegistry.DurableRecord owner) {
            return new VirtualPendingIntentRegistry.DurableRecord(value.tokenId(), value.kind(), value.requestCode(),
                    value.action(), value.component(), value.data(), value.filterIdentity(), value.flags(),
                    value.creatorPackage(), value.creatorUid(), value.requiredPermission(), owner.ownerProcessName(),
                    owner.ownerGeneration(), value.packageRevision(), value.payload(), value.sends(),
                    value.cancelled(), System.currentTimeMillis());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
