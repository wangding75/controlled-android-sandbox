package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

/** Contract checks for monotonic launch-budget propagation. */
public final class LaunchDeadlineSelfTest {
    public static void main(String[] args) {
        Bundle request = new Bundle();
        request.putLong(RuntimeKeys.LAUNCH_TOTAL_BUDGET_MS, 5_000L);
        LaunchDeadline deadline = LaunchDeadline.start(request);
        deadline.attach(request);
        require(request.getLong(RuntimeKeys.LAUNCH_TOTAL_BUDGET_MS, 0L) == 5_000L,
                "budget is preserved");
        require(deadline.remainingMs() > 0L, "fresh deadline has remaining time");

        Bundle expired = new Bundle();
        expired.putLong(RuntimeKeys.LAUNCH_TOTAL_BUDGET_MS, 5_000L);
        expired.putLong(RuntimeKeys.LAUNCH_DEADLINE_AT_ELAPSED_MS,
                android.os.SystemClock.elapsedRealtime() - 1L);
        require(LaunchDeadline.start(expired).remainingMs() == 0L,
                "expired propagated deadline is not extended");

        Bundle details = new Bundle();
        LaunchDeadline.annotate(details, request);
        require(details.containsKey(RuntimeKeys.LAUNCH_STAGE_START_REMAINING_MS)
                        && details.containsKey(RuntimeKeys.LAUNCH_STAGE_END_REMAINING_MS),
                "stage remaining evidence is emitted");
        System.out.println("PASS launch deadline self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
