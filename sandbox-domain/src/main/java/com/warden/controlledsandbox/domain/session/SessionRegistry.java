package com.warden.controlledsandbox.domain.session;

import com.warden.controlledsandbox.domain.port.SessionMetricsRepository;
import com.warden.controlledsandbox.domain.port.TokenGenerator;
import com.warden.controlledsandbox.domain.process.SlotPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe source of truth for per-declared-process leases and generations. */
public final class SessionRegistry implements SessionMetricsRepository {
    private static final int MIN_HISTORY_ENTRIES = 64;
    private static final int HISTORY_ENTRIES_PER_SLOT = 64;
    private final SlotPool slots;
    private final int maxEntries;
    private final TokenGenerator tokenGenerator;
    private final Map<String, GuestSession> sessions = new LinkedHashMap<>();

    public SessionRegistry(int slotCount, TokenGenerator tokenGenerator) {
        if (tokenGenerator == null) throw new IllegalArgumentException("tokenGenerator is required");
        slots = new SlotPool(slotCount);
        maxEntries = Math.max(MIN_HISTORY_ENTRIES, Math.multiplyExact(slotCount, HISTORY_ENTRIES_PER_SLOT));
        this.tokenGenerator = tokenGenerator;
    }

    public synchronized GuestSession allocate(String packageName, int virtualUserId, long nowMs) {
        return allocate(packageName, virtualUserId, packageName, "legacy", nowMs);
    }

    public synchronized GuestSession allocate(String packageName, int virtualUserId,
                                              String processName, long nowMs) {
        return allocate(packageName, virtualUserId, processName, "legacy", nowMs);
    }

    public synchronized GuestSession allocate(String packageName, int virtualUserId,
                                              String processName, String packageRevision, long nowMs) {
        String key = key(packageName, virtualUserId, processName);
        GuestSession existing = sessions.get(key);
        if (existing != null && existing.state() != SessionState.STOPPED && existing.state() != SessionState.FAILED) {
            if (!existing.packageRevision().equals(packageRevision)) {
                throw new IllegalStateException("SESSION_REVISION_MISMATCH expected="
                        + existing.packageRevision() + " actual=" + packageRevision);
            }
            return existing;
        }
        if (existing == null) {
            sessions.entrySet().removeIf(entry -> entry.getValue().state() == SessionState.STOPPED
                    || entry.getValue().state() == SessionState.FAILED);
            if (sessions.size() >= maxEntries) throw new IllegalStateException("SESSION_HISTORY_LIMIT_EXCEEDED");
        }
        int slot = slots.reserve(slotOwner(packageName, processName), virtualUserId);
        if (slot < 0) throw new IllegalStateException("NO_PROCESS_SLOT");
        String sessionId = nextUniqueSessionId();
        GuestSession created = new GuestSession(sessionId, packageName, virtualUserId,
                processName, packageRevision, slot, 1, SessionState.ALLOCATED, nowMs, "");
        sessions.put(key, created);
        return created;
    }

    public synchronized GuestSession get(String packageName, int virtualUserId) {
        return get(packageName, virtualUserId, packageName);
    }

    public synchronized GuestSession get(String packageName, int virtualUserId, String processName) {
        return sessions.get(key(packageName, virtualUserId, processName));
    }

    public synchronized List<GuestSession> getAll(String packageName, int virtualUserId) {
        List<GuestSession> out = new ArrayList<>();
        for (GuestSession session : sessions.values()) {
            if (session.packageName().equals(packageName) && session.virtualUserId() == virtualUserId) out.add(session);
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized GuestSession transition(String packageName, int virtualUserId,
                                                long expectedGeneration, SessionState next,
                                                long nowMs, String failure) {
        return transition(packageName, virtualUserId, packageName, expectedGeneration, next, nowMs, failure);
    }

    public synchronized GuestSession transition(String packageName, int virtualUserId, String processName,
                                                long expectedGeneration, SessionState next,
                                                long nowMs, String failure) {
        String key = key(packageName, virtualUserId, processName);
        GuestSession current = requireSession(key);
        requireGeneration(current, expectedGeneration);
        GuestSession updated = current.transition(next, nowMs, failure);
        sessions.put(key, updated);
        if (next == SessionState.STOPPED || next == SessionState.FAILED) {
            slots.release(slotOwner(packageName, processName), virtualUserId);
        }
        return updated;
    }

    public synchronized GuestSession markProcessDied(String packageName, int virtualUserId,
                                                     long expectedGeneration, long nowMs,
                                                     String reason) {
        return markProcessDied(packageName, virtualUserId, packageName, expectedGeneration, nowMs, reason);
    }

    public synchronized GuestSession markProcessDied(String packageName, int virtualUserId,
                                                     String processName, long expectedGeneration,
                                                     long nowMs, String reason) {
        String key = key(packageName, virtualUserId, processName);
        GuestSession current = requireSession(key);
        requireGeneration(current, expectedGeneration);
        if (current.state() == SessionState.STOPPING) {
            return transition(packageName, virtualUserId, processName, expectedGeneration,
                    SessionState.STOPPED, nowMs, reason);
        }
        if (current.state() != SessionState.READY && current.state() != SessionState.ACTIVE) {
            throw new IllegalStateException("Process death is invalid in state " + current.state());
        }
        GuestSession updated = current.transition(SessionState.RECOVERING, nowMs, reason);
        sessions.put(key, updated);
        return updated;
    }

    public synchronized GuestSession beginRecovery(String packageName, int virtualUserId,
                                                   long expectedGeneration, long nowMs) {
        return beginRecovery(packageName, virtualUserId, packageName, expectedGeneration, nowMs);
    }

    public synchronized GuestSession beginRecovery(String packageName, int virtualUserId,
                                                   String processName, long expectedGeneration,
                                                   long nowMs) {
        String key = key(packageName, virtualUserId, processName);
        GuestSession current = requireSession(key);
        requireGeneration(current, expectedGeneration);
        GuestSession updated = current.nextGeneration(nowMs);
        sessions.put(key, updated);
        return updated;
    }

    /** Locate the current lease by host process slot. */
    public synchronized GuestSession findByProcessSlot(int processSlot) {
        for (GuestSession session : sessions.values()) {
            if (session.processSlot() == processSlot
                    && session.state() != SessionState.STOPPED
                    && session.state() != SessionState.FAILED) {
                return session;
            }
        }
        return null;
    }

    /** Apply a host-process disconnect to the state machine without guessing at recovery. */
    public synchronized GuestSession markSlotDisconnected(int processSlot, long nowMs, String reason) {
        GuestSession current = findByProcessSlot(processSlot);
        if (current == null) return null;
        SessionState next;
        switch (current.state()) {
            case READY:
            case ACTIVE:
                next = SessionState.RECOVERING;
                break;
            case STOPPING:
                next = SessionState.STOPPED;
                break;
            case ALLOCATED:
            case PREPARING:
                next = SessionState.FAILED;
                break;
            case RECOVERING:
                return current;
            default:
                return current;
        }
        GuestSession updated = current.transition(next, nowMs, reason);
        sessions.put(key(current.packageName(), current.virtualUserId(), current.processName()), updated);
        if (next == SessionState.STOPPED || next == SessionState.FAILED) {
            slots.release(slotOwner(current.packageName(), current.processName()), current.virtualUserId());
        }
        return updated;
    }

    public synchronized List<GuestSession> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(sessions.values()));
    }

    @Override public synchronized int capacity() { return slots.capacity(); }
    @Override public synchronized int used() { return slots.used(); }
    @Override public synchronized int count() { return sessions.size(); }

    private String nextUniqueSessionId() {
        for (int attempt = 0; attempt < 16; attempt++) {
            String candidate = requireToken(tokenGenerator.nextToken("session"));
            boolean duplicate = false;
            for (GuestSession session : sessions.values()) {
                if (session.sessionId().equals(candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) return candidate;
        }
        throw new IllegalStateException("TOKEN_GENERATOR_SESSION_ID_COLLISION");
    }

    private static String requireToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalStateException("TOKEN_GENERATOR_RETURNED_EMPTY_SESSION_ID");
        }
        if (token.length() > 128) throw new IllegalStateException("SESSION_ID_TOO_LONG");
        return token;
    }

    private GuestSession requireSession(String key) {
        GuestSession session = sessions.get(key);
        if (session == null) throw new IllegalStateException("SESSION_NOT_FOUND");
        return session;
    }

    private static void requireGeneration(GuestSession current, long expected) {
        if (current.generation() != expected) {
            throw new IllegalStateException("STALE_GENERATION expected=" + current.generation() + " actual=" + expected);
        }
    }

    private static String key(String packageName, int userId, String processName) {
        return userId + ":" + packageName + ":" + processName;
    }

    private static String slotOwner(String packageName, String processName) {
        return packageName + "@" + processName;
    }
}
