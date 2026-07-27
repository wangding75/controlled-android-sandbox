package com.warden.controlledsandbox.runtime.component.receiver;

import com.warden.controlledsandbox.domain.component.receiver.OrderedBroadcastState;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.port.TokenGenerator;
import com.warden.controlledsandbox.domain.session.GuestSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Broker authority for one-shot ordered Receiver completion tokens. */
public final class OrderedReceiverTokenRegistry {
    public static final int MAX_ACTIVE = 256;
    public static final long MAX_TIMEOUT_MS = 10_000L;
    public static final long DEFAULT_TIMEOUT_MS = 10_000L;
    private static final long TERMINAL_RETENTION_MS = 60_000L;
    private static final int TOKEN_ATTEMPTS = 8;

    private final Clock clock;
    private final TokenGenerator tokens;
    private final Map<String, Record> records = new LinkedHashMap<>();

    public OrderedReceiverTokenRegistry(Clock clock, TokenGenerator tokens) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    public synchronized Lease issue(GuestSession target, String receiverClass, long requestedTimeoutMs) {
        Objects.requireNonNull(target, "target");
        String receiver = requireText(receiverClass, "receiverClass", 512);
        purgeTerminalLocked(clock.nowMillis());
        if (activeCountLocked() >= MAX_ACTIVE) {
            throw new IllegalStateException("ORDERED_RECEIVER_CAPACITY_EXCEEDED");
        }
        long timeoutMs = normalizeTimeout(requestedTimeoutMs);
        long issuedAt = clock.nowMillis();
        long deadline = safeAdd(issuedAt, timeoutMs);
        String token = uniqueTokenLocked();
        Record record = new Record(token, target.packageName(), target.virtualUserId(),
                target.sessionId(), target.generation(), receiver, issuedAt, deadline);
        records.put(token, record);
        return record.lease();
    }

    public synchronized CompletionDecision complete(String token, Identity identity,
                                                      OrderedBroadcastState.ResultUpdate update) {
        Objects.requireNonNull(identity, "identity");
        Record record = records.get(requireText(token, "token", 256));
        if (record == null) return CompletionDecision.unknown();
        if (!record.matches(identity)) return CompletionDecision.identityMismatch();
        if (record.state != State.PENDING) return CompletionDecision.terminal(record.state);
        long now = clock.nowMillis();
        if (now >= record.deadlineMs) {
            finishLocked(record, State.TIMED_OUT, null, "ORDERED_RECEIVER_TIMEOUT", now);
            return CompletionDecision.terminal(State.TIMED_OUT);
        }
        finishLocked(record, State.COMPLETED, update, "", now);
        return CompletionDecision.acceptedDecision();
    }

    public synchronized CompletionDecision reject(String token, Identity identity, String reason) {
        Objects.requireNonNull(identity, "identity");
        Record record = records.get(requireText(token, "token", 256));
        if (record == null) return CompletionDecision.unknown();
        if (!record.matches(identity)) return CompletionDecision.identityMismatch();
        if (record.state != State.PENDING) return CompletionDecision.terminal(record.state);
        finishLocked(record, State.CANCELLED, null, normalizeReason(reason), clock.nowMillis());
        return new CompletionDecision(false, "ORDERED_RECEIVER_RESULT_REJECTED", State.CANCELLED);
    }

    public AwaitResult await(Lease lease) throws InterruptedException {
        Objects.requireNonNull(lease, "lease");
        Record record;
        synchronized (this) {
            record = records.get(lease.token());
            if (record == null || !record.lease().equals(lease)) {
                return AwaitResult.failure(State.CANCELLED, "ORDERED_RECEIVER_TOKEN_UNKNOWN");
            }
        }
        long remaining = Math.max(0L, lease.deadlineMs() - clock.nowMillis());
        try {
            Terminal terminal = record.completion.get(remaining, TimeUnit.MILLISECONDS);
            return terminal.toAwaitResult();
        } catch (TimeoutException timeout) {
            synchronized (this) {
                if (record.state == State.PENDING) {
                    finishLocked(record, State.TIMED_OUT, null, "ORDERED_RECEIVER_TIMEOUT",
                            Math.max(clock.nowMillis(), lease.deadlineMs()));
                }
            }
            Terminal terminal = record.completion.getNow(
                    new Terminal(State.TIMED_OUT, null, "ORDERED_RECEIVER_TIMEOUT"));
            return terminal.toAwaitResult();
        } catch (java.util.concurrent.ExecutionException impossible) {
            return AwaitResult.failure(State.CANCELLED, "ORDERED_RECEIVER_COMPLETION_FAILED");
        }
    }

    public synchronized boolean cancel(Lease lease, String reason) {
        if (lease == null) return false;
        Record record = records.get(lease.token());
        if (record == null || record.state != State.PENDING || !record.lease().equals(lease)) return false;
        finishLocked(record, State.CANCELLED, null, normalizeReason(reason), clock.nowMillis());
        return true;
    }

    public synchronized int cancelSession(GuestSession session, String reason) {
        if (session == null) return 0;
        return cancelMatching(record -> record.sessionId.equals(session.sessionId())
                && record.generation == session.generation(), reason);
    }

    public synchronized int cancelInstance(String packageName, int virtualUserId, String reason) {
        String pkg = requireText(packageName, "packageName", 255);
        return cancelMatching(record -> record.packageName.equals(pkg)
                && record.virtualUserId == virtualUserId, reason);
    }

    public synchronized int cancelAll(String reason) {
        return cancelMatching(record -> true, reason);
    }

    public synchronized int purgeExpired() {
        long now = clock.nowMillis();
        int expired = 0;
        for (Record record : records.values()) {
            if (record.state == State.PENDING && now >= record.deadlineMs) {
                finishLocked(record, State.TIMED_OUT, null, "ORDERED_RECEIVER_TIMEOUT", now);
                expired++;
            }
        }
        purgeTerminalLocked(now);
        return expired;
    }

    public synchronized int pendingCount() { return activeCountLocked(); }

    public synchronized List<Lease> pendingLeases() {
        ArrayList<Lease> result = new ArrayList<>();
        for (Record record : records.values()) {
            if (record.state == State.PENDING) result.add(record.lease());
        }
        return Collections.unmodifiableList(result);
    }

    private int cancelMatching(Matcher matcher, String reason) {
        String normalized = normalizeReason(reason);
        int cancelled = 0;
        long now = clock.nowMillis();
        for (Record record : records.values()) {
            if (record.state == State.PENDING && matcher.matches(record)) {
                finishLocked(record, State.CANCELLED, null, normalized, now);
                cancelled++;
            }
        }
        purgeTerminalLocked(now);
        return cancelled;
    }

    private void finishLocked(Record record, State state,
                              OrderedBroadcastState.ResultUpdate update,
                              String reason, long terminalAtMs) {
        if (record.state != State.PENDING) return;
        record.state = state;
        record.terminalAtMs = terminalAtMs;
        record.completion.complete(new Terminal(state, update, normalizeReason(reason)));
    }

    private int activeCountLocked() {
        int count = 0;
        for (Record record : records.values()) if (record.state == State.PENDING) count++;
        return count;
    }

    private void purgeTerminalLocked(long now) {
        records.entrySet().removeIf(entry -> entry.getValue().state != State.PENDING
                && now - entry.getValue().terminalAtMs > TERMINAL_RETENTION_MS);
    }

    private String uniqueTokenLocked() {
        for (int attempt = 0; attempt < TOKEN_ATTEMPTS; attempt++) {
            String token = requireText(tokens.nextToken("ordered-receiver"), "generatedToken", 256);
            if (!records.containsKey(token)) return token;
        }
        throw new IllegalStateException("ORDERED_RECEIVER_TOKEN_COLLISION");
    }

    private static long normalizeTimeout(long requested) {
        if (requested <= 0) return DEFAULT_TIMEOUT_MS;
        return Math.min(requested, MAX_TIMEOUT_MS);
    }

    private static long safeAdd(long value, long increment) {
        if (increment > 0 && value > Long.MAX_VALUE - increment) return Long.MAX_VALUE;
        return value + increment;
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) return "ORDERED_RECEIVER_CANCELLED";
        String value = reason.trim();
        return value.length() <= 256 ? value : value.substring(0, 256);
    }

    private static String requireText(String value, String name) {
        return requireText(value, name, 512);
    }

    private static String requireText(String value, String name, int max) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }

    private interface Matcher { boolean matches(Record record); }

    public enum State { PENDING, COMPLETED, TIMED_OUT, CANCELLED }

    public record Identity(String packageName, int virtualUserId, String sessionId,
                           long generation, String receiverClass) {
        public Identity {
            packageName = requireText(packageName, "packageName", 255);
            if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
            sessionId = requireText(sessionId, "sessionId", 256);
            if (generation < 1) throw new IllegalArgumentException("generation must be positive");
            receiverClass = requireText(receiverClass, "receiverClass", 512);
        }
    }

    public record Lease(String token, String packageName, int virtualUserId,
                        String sessionId, long generation, String receiverClass,
                        long issuedAtMs, long deadlineMs) { }

    public record AwaitResult(boolean completed, State state,
                              OrderedBroadcastState.ResultUpdate update, String reason) {
        static AwaitResult completed(OrderedBroadcastState.ResultUpdate update) {
            return new AwaitResult(true, State.COMPLETED, update, "");
        }
        static AwaitResult failure(State state, String reason) {
            return new AwaitResult(false, state, null, normalizeReason(reason));
        }
    }

    public record CompletionDecision(boolean accepted, String status, State terminalState) {
        static CompletionDecision acceptedDecision() {
            return new CompletionDecision(true, "ORDERED_RECEIVER_COMPLETED", State.COMPLETED);
        }
        static CompletionDecision unknown() {
            return new CompletionDecision(false, "ORDERED_RECEIVER_TOKEN_UNKNOWN", null);
        }
        static CompletionDecision identityMismatch() {
            return new CompletionDecision(false, "ORDERED_RECEIVER_IDENTITY_MISMATCH", null);
        }
        static CompletionDecision terminal(State state) {
            String status = state == State.COMPLETED ? "ORDERED_RECEIVER_REPLAY"
                    : state == State.TIMED_OUT ? "ORDERED_RECEIVER_LATE_COMPLETION"
                    : "ORDERED_RECEIVER_CANCELLED";
            return new CompletionDecision(false, status, state);
        }
    }

    private static final class Terminal {
        final State state;
        final OrderedBroadcastState.ResultUpdate update;
        final String reason;

        Terminal(State state, OrderedBroadcastState.ResultUpdate update, String reason) {
            this.state = state;
            this.update = update;
            this.reason = reason;
        }

        AwaitResult toAwaitResult() {
            return state == State.COMPLETED
                    ? AwaitResult.completed(update)
                    : AwaitResult.failure(state, reason);
        }
    }

    private static final class Record {
        final String token;
        final String packageName;
        final int virtualUserId;
        final String sessionId;
        final long generation;
        final String receiverClass;
        final long issuedAtMs;
        final long deadlineMs;
        final CompletableFuture<Terminal> completion = new CompletableFuture<>();
        State state = State.PENDING;
        long terminalAtMs;

        Record(String token, String packageName, int virtualUserId, String sessionId,
               long generation, String receiverClass, long issuedAtMs, long deadlineMs) {
            this.token = token;
            this.packageName = packageName;
            this.virtualUserId = virtualUserId;
            this.sessionId = sessionId;
            this.generation = generation;
            this.receiverClass = receiverClass;
            this.issuedAtMs = issuedAtMs;
            this.deadlineMs = deadlineMs;
        }

        boolean matches(Identity identity) {
            return packageName.equals(identity.packageName())
                    && virtualUserId == identity.virtualUserId()
                    && sessionId.equals(identity.sessionId())
                    && generation == identity.generation()
                    && receiverClass.equals(identity.receiverClass());
        }

        Lease lease() {
            return new Lease(token, packageName, virtualUserId, sessionId, generation,
                    receiverClass, issuedAtMs, deadlineMs);
        }
    }
}
