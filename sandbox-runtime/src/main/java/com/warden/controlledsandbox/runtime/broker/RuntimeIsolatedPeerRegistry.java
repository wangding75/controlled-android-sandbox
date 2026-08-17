package com.warden.controlledsandbox.runtime.broker;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Broker-side isolated runtime peer admission.
 *
 * <p>Isolated workers receive a platform-assigned UID and cannot present the host signature
 * permission. Admission is therefore a combination of a session-issued capability token, the
 * expected slot/process, generation, and the Binder caller UID recorded at registration. A raw
 * isolated-UID range check is not an identity contract and is rejected.
 */
public final class RuntimeIsolatedPeerRegistry {
    public enum Kind {
        HOST_BROKER,
        COMPANION32,
        ORDINARY_GUEST,
        ISOLATED_GUEST,
        HOSTILE_ISOLATED_WORKER,
        ORDINARY_ISOLATED_SERVICE
    }

    public static final class Lease {
        public final String sessionId;
        public final long generation;
        public final int slot;
        public final String token;
        public final String processName;
        public final String packageName;
        public final Kind kind;
        public final int isolatedUid;

        public Lease(String sessionId, long generation, int slot, String token, String processName,
                     String packageName, Kind kind, int isolatedUid) {
            this.sessionId = required(sessionId, "sessionId");
            if (generation < 1) throw new IllegalArgumentException("generation must be positive");
            if (slot < 0) throw new IllegalArgumentException("slot is invalid");
            this.generation = generation;
            this.slot = slot;
            this.token = required(token, "token");
            this.processName = required(processName, "processName");
            this.packageName = required(packageName, "packageName");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.isolatedUid = isolatedUid;
        }

        public boolean pending() { return isolatedUid == 0; }

        public Lease boundTo(int uid) {
            if (uid <= 0) throw new SecurityException("ISOLATED_PEER_UID_INVALID:" + uid);
            return new Lease(sessionId, generation, slot, token, processName, packageName, kind, uid);
        }
    }

    private final ConcurrentMap<String, Lease> bySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, String> sessionByUid = new ConcurrentHashMap<>();

    public void publishPending(Lease pending) {
        if (pending == null || !pending.pending()) {
            throw new IllegalArgumentException("pending isolated lease is required");
        }
        String key = key(pending.sessionId, pending.generation);
        Lease previous = bySession.put(key, pending);
        if (previous != null && !previous.pending()) {
            sessionByUid.remove(previous.isolatedUid, key);
        }
    }

    public Lease completeRegistration(String sessionId, long generation, int slot, String token,
                                      int callerUid, int hostUid) {
        if (callerUid <= 0) throw new SecurityException("ISOLATED_PEER_UID_INVALID:" + callerUid);
        if (callerUid == hostUid) {
            throw new SecurityException("ISOLATED_PEER_UID_EQUALS_HOST_UID:" + callerUid);
        }
        String key = key(sessionId, generation);
        Lease pending = bySession.get(key);
        if (pending == null) throw new SecurityException("ISOLATED_PEER_LEASE_NOT_FOUND");
        if (pending.slot != slot) throw new SecurityException("ISOLATED_PEER_SLOT_MISMATCH");
        if (!constantEquals(pending.token, token)) {
            throw new SecurityException("ISOLATED_PEER_TOKEN_MISMATCH");
        }
        if (!pending.pending()) {
            if (pending.isolatedUid != callerUid) {
                throw new SecurityException("ISOLATED_PEER_UID_REBIND_REJECTED:" + callerUid);
            }
            return pending;
        }
        String existing = sessionByUid.putIfAbsent(callerUid, key);
        if (existing != null && !existing.equals(key)) {
            throw new SecurityException("ISOLATED_PEER_UID_ALREADY_BOUND");
        }
        Lease bound = pending.boundTo(callerUid);
        if (!bySession.replace(key, pending, bound)) {
            sessionByUid.remove(callerUid, key);
            Lease raced = bySession.get(key);
            if (raced != null && raced.isolatedUid == callerUid) return raced;
            throw new SecurityException("ISOLATED_PEER_REGISTRATION_RACE");
        }
        return bound;
    }

    public boolean isRegisteredIsolatedPeer(int uid) {
        if (uid <= 0) return false;
        String key = sessionByUid.get(uid);
        if (key == null) return false;
        Lease lease = bySession.get(key);
        return lease != null && !lease.pending() && lease.isolatedUid == uid;
    }

    public Lease requireRegistered(int uid) {
        if (!isRegisteredIsolatedPeer(uid)) {
            throw new SecurityException("UNTRUSTED_RUNTIME_PEER_UID:" + uid);
        }
        return bySession.get(sessionByUid.get(uid));
    }

    public void requireRegisteredUid(int uid, String sessionId, long generation) {
        Lease lease = requireRegistered(uid);
        if (!lease.sessionId.equals(sessionId) || lease.generation != generation) {
            throw new SecurityException("ISOLATED_PEER_SESSION_MISMATCH");
        }
    }

    public void revoke(String sessionId, long generation) {
        if (sessionId == null || sessionId.isEmpty() || generation < 1) return;
        String key = key(sessionId, generation);
        Lease removed = bySession.remove(key);
        if (removed != null && !removed.pending()) {
            sessionByUid.remove(removed.isolatedUid, key);
        }
    }

    public void revokeSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        String prefix = sessionId + "@";
        for (String key : bySession.keySet()) {
            if (!key.startsWith(prefix)) continue;
            Lease removed = bySession.remove(key);
            if (removed != null && !removed.pending()) {
                sessionByUid.remove(removed.isolatedUid, key);
            }
        }
    }

    public void clear() {
        bySession.clear();
        sessionByUid.clear();
    }

    public static boolean looksLikeIsolatedRange(int uid) {
        return uid >= 99_000 && uid <= 99_999;
    }

    private static String key(String sessionId, long generation) {
        return required(sessionId, "sessionId") + "@" + generation;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static boolean constantEquals(String left, String right) {
        if (left == null || right == null) return false;
        byte[] a = left.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = right.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
