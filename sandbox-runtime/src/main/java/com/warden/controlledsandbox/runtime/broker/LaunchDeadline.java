package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

/** Monotonic launch budget shared by Broker stages and Guest Binder calls. */
final class LaunchDeadline {
    static final long DEFAULT_BUDGET_MS = 30_000L;
    static final long MIN_BUDGET_MS = 1_000L;
    static final long MAX_BUDGET_MS = 120_000L;

    private final long totalBudgetMs;
    private final long deadlineAtElapsedMs;

    private LaunchDeadline(long totalBudgetMs, long deadlineAtElapsedMs) {
        this.totalBudgetMs = totalBudgetMs;
        this.deadlineAtElapsedMs = deadlineAtElapsedMs;
    }

    static LaunchDeadline start(Bundle request) {
        long now = android.os.SystemClock.elapsedRealtime();
        long requested = request == null ? DEFAULT_BUDGET_MS
                : request.getLong(RuntimeKeys.LAUNCH_TOTAL_BUDGET_MS, DEFAULT_BUDGET_MS);
        long budget = Math.max(MIN_BUDGET_MS, Math.min(MAX_BUDGET_MS, requested));
        long suppliedDeadline = request == null ? 0L
                : request.getLong(RuntimeKeys.LAUNCH_DEADLINE_AT_ELAPSED_MS, 0L);
        if (request != null && request.containsKey(RuntimeKeys.LAUNCH_DEADLINE_AT_ELAPSED_MS)) {
            if (suppliedDeadline <= now) return new LaunchDeadline(budget, suppliedDeadline);
            long remaining = suppliedDeadline - now;
            budget = Math.max(MIN_BUDGET_MS, Math.min(MAX_BUDGET_MS, remaining));
            return new LaunchDeadline(budget, suppliedDeadline);
        }
        return new LaunchDeadline(budget, now + budget);
    }

    void attach(Bundle request) {
        if (request == null) return;
        request.putLong(RuntimeKeys.LAUNCH_TOTAL_BUDGET_MS, totalBudgetMs);
        request.putLong(RuntimeKeys.LAUNCH_DEADLINE_AT_ELAPSED_MS, deadlineAtElapsedMs);
        request.putString(RuntimeKeys.LAUNCH_TIMEOUT_OWNER, "BROKER_LAUNCH");
    }

    long remainingMs() {
        return Math.max(0L, deadlineAtElapsedMs - android.os.SystemClock.elapsedRealtime());
    }

    static long remaining(Bundle request) {
        if (request == null) return 0L;
        long deadline = request.getLong(RuntimeKeys.LAUNCH_DEADLINE_AT_ELAPSED_MS, 0L);
        return deadline <= 0L ? 0L
                : Math.max(0L, deadline - android.os.SystemClock.elapsedRealtime());
    }

    static void owner(Bundle request, String owner) {
        if (request != null) request.putString(RuntimeKeys.LAUNCH_TIMEOUT_OWNER,
                owner == null || owner.trim().isEmpty() ? "BROKER_LAUNCH" : owner.trim());
    }

    static void annotate(Bundle details, Bundle source) {
        if (details == null || source == null) return;
        long total = source.getLong(RuntimeKeys.LAUNCH_TOTAL_BUDGET_MS, 0L);
        long deadline = source.getLong(RuntimeKeys.LAUNCH_DEADLINE_AT_ELAPSED_MS, 0L);
        if (total <= 0L || deadline <= 0L) return;
        long remaining = Math.max(0L, deadline - android.os.SystemClock.elapsedRealtime());
        details.putLong(RuntimeKeys.LAUNCH_TOTAL_BUDGET_MS, total);
        details.putLong(RuntimeKeys.LAUNCH_STAGE_START_REMAINING_MS, remaining);
        details.putLong(RuntimeKeys.LAUNCH_STAGE_END_REMAINING_MS, remaining);
        details.putString(RuntimeKeys.LAUNCH_TIMEOUT_OWNER,
                source.getString(RuntimeKeys.LAUNCH_TIMEOUT_OWNER, "BROKER_LAUNCH"));
    }
}
