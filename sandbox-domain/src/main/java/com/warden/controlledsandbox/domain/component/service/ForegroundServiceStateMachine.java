package com.warden.controlledsandbox.domain.component.service;

/**
 * Android-independent foreground-service promotion and notification ownership policy.
 *
 * <p>The model intentionally separates a startForegroundService request from the later foreground
 * promotion. It does not claim that Android SystemUI or ActivityManager enforcement has run.</p>
 */
public final class ForegroundServiceStateMachine {
    public static final long DEFAULT_PROMOTION_TIMEOUT_MS = 5_000L;
    public static final long MAX_PROMOTION_TIMEOUT_MS = 10_000L;
    public static final int MAX_NOTIFICATION_ID = 0x00ff_ffff;
    public static final int MAX_REASON_CHARS = 256;
    public static final int MAX_NOTIFICATION_TAG_CHARS = 128;

    public enum State { NONE, PENDING, ACTIVE, DEMOTED, TIMED_OUT, TERMINATED }

    private State state = State.NONE;
    private long requestedAtMs;
    private long promotionDeadlineMs;
    private long promotedAtMs;
    private int declaredTypeMask;
    private int activeTypeMask;
    private int notificationId;
    private String notificationTag = "";
    private boolean backgroundStartAllowed = true;
    private String exemptionReason = "";
    private String terminalReason = "";

    public void requestStart(long nowMs, long requestedTimeoutMs, boolean backgroundAllowed,
                             String exemption, int declaredTypes) {
        requireTime(nowMs);
        int normalizedDeclared = requireTypeMask(declaredTypes, "declaredTypeMask");
        String normalizedExemption = bounded(exemption, MAX_REASON_CHARS);
        if (!backgroundAllowed && normalizedExemption.isEmpty()) {
            throw new SecurityException("FOREGROUND_SERVICE_BACKGROUND_START_NOT_ALLOWED");
        }
        backgroundStartAllowed = backgroundAllowed;
        exemptionReason = normalizedExemption;
        declaredTypeMask = normalizedDeclared;
        terminalReason = "";
        if (state == State.ACTIVE) return;
        requestedAtMs = nowMs;
        promotionDeadlineMs = safeAdd(nowMs, normalizeTimeout(requestedTimeoutMs));
        promotedAtMs = 0L;
        activeTypeMask = 0;
        notificationId = 0;
        notificationTag = "";
        state = State.PENDING;
    }

    public void promote(long nowMs, int requestedTypes, int id, String tag) {
        requireTime(nowMs);
        if (state != State.PENDING && state != State.ACTIVE && state != State.DEMOTED) {
            throw new IllegalStateException("FOREGROUND_SERVICE_NOT_PENDING");
        }
        if (state == State.PENDING && nowMs >= promotionDeadlineMs) {
            timeout("FOREGROUND_SERVICE_PROMOTION_TIMEOUT");
            throw new IllegalStateException("FOREGROUND_SERVICE_PROMOTION_TIMEOUT");
        }
        if (id < 1 || id > MAX_NOTIFICATION_ID) {
            throw new IllegalArgumentException("FOREGROUND_SERVICE_NOTIFICATION_ID_INVALID");
        }
        int types = requireTypeMask(requestedTypes, "requestedTypeMask");
        if ((types & ~declaredTypeMask) != 0) {
            throw new SecurityException("FOREGROUND_SERVICE_TYPE_NOT_DECLARED");
        }
        activeTypeMask = types;
        notificationId = id;
        notificationTag = bounded(tag, MAX_NOTIFICATION_TAG_CHARS);
        promotedAtMs = nowMs;
        terminalReason = "";
        state = State.ACTIVE;
    }

    public void demote(boolean removeNotification, String reason) {
        if (state == State.NONE || state == State.TERMINATED || state == State.TIMED_OUT) return;
        activeTypeMask = 0;
        if (removeNotification) {
            notificationId = 0;
            notificationTag = "";
        }
        terminalReason = bounded(reason, MAX_REASON_CHARS);
        state = State.DEMOTED;
    }

    public boolean expire(long nowMs) {
        requireTime(nowMs);
        if (state != State.PENDING || nowMs < promotionDeadlineMs) return false;
        timeout("FOREGROUND_SERVICE_PROMOTION_TIMEOUT");
        return true;
    }

    public void terminate(String reason) {
        activeTypeMask = 0;
        notificationId = 0;
        notificationTag = "";
        terminalReason = bounded(reason, MAX_REASON_CHARS);
        state = State.TERMINATED;
    }

    public boolean requested() {
        return state == State.PENDING || state == State.ACTIVE || state == State.DEMOTED;
    }

    public boolean active() { return state == State.ACTIVE; }
    public boolean pending() { return state == State.PENDING; }

    public Snapshot snapshot() {
        return new Snapshot(state, requestedAtMs, promotionDeadlineMs, promotedAtMs,
                declaredTypeMask, activeTypeMask, notificationId, notificationTag,
                backgroundStartAllowed, exemptionReason, terminalReason);
    }

    private void timeout(String reason) {
        activeTypeMask = 0;
        notificationId = 0;
        notificationTag = "";
        terminalReason = reason;
        state = State.TIMED_OUT;
    }

    private static long normalizeTimeout(long value) {
        if (value <= 0) return DEFAULT_PROMOTION_TIMEOUT_MS;
        return Math.min(value, MAX_PROMOTION_TIMEOUT_MS);
    }

    private static int requireTypeMask(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must be non-negative");
        return value;
    }

    private static void requireTime(long value) {
        if (value < 0) throw new IllegalArgumentException("time must be non-negative");
    }

    private static String bounded(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("value is too long");
        return normalized;
    }

    private static long safeAdd(long value, long increment) {
        return increment > 0 && value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    public record Snapshot(State state, long requestedAtMs, long promotionDeadlineMs,
                           long promotedAtMs, int declaredTypeMask, int activeTypeMask,
                           int notificationId, String notificationTag,
                           boolean backgroundStartAllowed, String exemptionReason,
                           String terminalReason) {
        public boolean active() { return state == State.ACTIVE; }
        public boolean pending() { return state == State.PENDING; }
    }
}
