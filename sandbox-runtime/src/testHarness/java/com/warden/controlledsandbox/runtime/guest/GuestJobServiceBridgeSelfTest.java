package com.warden.controlledsandbox.runtime.guest;

import android.app.job.JobParameters;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class GuestJobServiceBridgeSelfTest {
    public static void main(String[] args) {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean reschedule = new AtomicBoolean(true);
        AtomicInteger finishes = new AtomicInteger();
        VirtualSystemServiceAuthority.JobExecution execution = new VirtualSystemServiceAuthority.JobExecution() {
            @Override public int guestJobId() { return 73; }
            @Override public long generation() { return 9L; }
            @Override public long dispatchToken() { return 44L; }
            @Override public boolean active() { return active.get(); }
            @Override public void finish(boolean needsReschedule) {
                if (!active.compareAndSet(true, false)) throw new AssertionError("duplicate finish");
                reschedule.set(needsReschedule); finishes.incrementAndGet();
            }
        };
        VirtualSystemServiceAuthority.JobParametersRecord record =
                new VirtualSystemServiceAuthority.JobParametersRecord(1001, 73, "guest",
                        null, null, null, 0, true, false, false,
                        List.of("content://guest/jobs/1"), List.of("guest.jobs"), null,
                        0, -1, "", 44L);
        JobParameters parameters = GuestJobServiceBridge.GuestJobParametersFactory.create(
                record, new android.os.Binder());
        require(parameters.getJobId() == 73, "Guest JobParameters must expose Guest job ID");

        AtomicInteger completed = new AtomicInteger();
        GuestJobServiceBridge.GuestJobCallbackBinder callback =
                new GuestJobServiceBridge.GuestJobCallbackBinder(73, execution,
                        completed::incrementAndGet);
        require(callback.active(), "fresh callback must be active");
        callback.finish(false);
        callback.finish(true);
        require(finishes.get() == 1 && completed.get() == 1 && !reschedule.get(),
                "jobFinished must be one-shot and preserve reschedule result");
        require(!callback.active(), "completed callback must be inactive");
        System.out.println("PASS Guest JobService bridge callback self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
