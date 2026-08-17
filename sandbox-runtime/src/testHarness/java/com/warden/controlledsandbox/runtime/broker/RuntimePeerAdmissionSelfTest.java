package com.warden.controlledsandbox.runtime.broker;

public final class RuntimePeerAdmissionSelfTest {
    private RuntimePeerAdmissionSelfTest() { }

    public static void main(String[] args) {
        RuntimeIsolatedPeerRegistry registry = new RuntimeIsolatedPeerRegistry();
        RuntimeIsolatedPeerRegistry.Lease pending = new RuntimeIsolatedPeerRegistry.Lease(
                "session-iso", 2L, 7, "capability-token-7",
                "com.example:isolated", "com.example",
                RuntimeIsolatedPeerRegistry.Kind.ISOLATED_GUEST, 0);
        registry.publishPending(pending);

        check(!registry.isRegisteredIsolatedPeer(99042), "pending lease must not admit a UID");
        check(!registry.isRegisteredIsolatedPeer(99_042)
                        || !RuntimeIsolatedPeerRegistry.looksLikeIsolatedRange(99042)
                        || !registry.isRegisteredIsolatedPeer(99001),
                "isolated UID range must not be an admission signal");
        check(!registry.isRegisteredIsolatedPeer(99001),
                "unregistered 99000-range UID must stay fail-closed");

        expectSecurity(() -> registry.completeRegistration(
                "session-iso", 2L, 7, "capability-token-7", 10086, 10086),
                "ISOLATED_PEER_UID_EQUALS_HOST_UID");
        expectSecurity(() -> registry.completeRegistration(
                "session-iso", 2L, 7, "wrong-token", 99042, 10086),
                "ISOLATED_PEER_TOKEN_MISMATCH");
        expectSecurity(() -> registry.completeRegistration(
                "session-iso", 2L, 8, "capability-token-7", 99042, 10086),
                "ISOLATED_PEER_SLOT_MISMATCH");
        expectSecurity(() -> registry.completeRegistration(
                "missing", 2L, 7, "capability-token-7", 99042, 10086),
                "ISOLATED_PEER_LEASE_NOT_FOUND");

        RuntimeIsolatedPeerRegistry.Lease bound = registry.completeRegistration(
                "session-iso", 2L, 7, "capability-token-7", 99042, 10086);
        check(bound.isolatedUid == 99042, "registered UID must be the Binder caller");
        check(registry.isRegisteredIsolatedPeer(99042), "registered isolated guest must be admitted");
        check(!registry.isRegisteredIsolatedPeer(99043), "other isolated UID must stay fail-closed");
        registry.requireRegisteredUid(99042, "session-iso", 2L);

        RuntimeIsolatedPeerRegistry.Lease again = registry.completeRegistration(
                "session-iso", 2L, 7, "capability-token-7", 99042, 10086);
        check(again.isolatedUid == 99042, "same UID re-register must be idempotent");
        expectSecurity(() -> registry.completeRegistration(
                "session-iso", 2L, 7, "capability-token-7", 99099, 10086),
                "ISOLATED_PEER_UID_REBIND_REJECTED");

        RuntimeIsolatedPeerRegistry.Lease hostile = new RuntimeIsolatedPeerRegistry.Lease(
                "session-hostile", 1L, 0, "hostile-token",
                "com.example:hostile", "com.example",
                RuntimeIsolatedPeerRegistry.Kind.HOSTILE_ISOLATED_WORKER, 0);
        registry.publishPending(hostile);
        registry.completeRegistration("session-hostile", 1L, 0, "hostile-token", 99010, 10086);
        check(registry.isRegisteredIsolatedPeer(99010), "hostile isolated worker uses the same contract");

        registry.revoke("session-iso", 2L);
        check(!registry.isRegisteredIsolatedPeer(99042), "revoked generation must fail closed");
        registry.revokeSession("session-hostile");
        check(!registry.isRegisteredIsolatedPeer(99010), "session revoke must drop hostile worker");

        System.out.println("PASS isolated runtime peer admission identity contract self-test");
    }

    private static void expectSecurity(Runnable action, String token) {
        try {
            action.run();
        } catch (SecurityException expected) {
            if (!String.valueOf(expected.getMessage()).contains(token)) {
                throw new AssertionError("wrong fail-closed error, expected " + token
                        + " got " + expected.getMessage(), expected);
            }
            return;
        }
        throw new AssertionError("expected fail-closed: " + token);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
