package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Enqueues two real JobWorkItems for the API32 JobService transport probe. */
public final class FixtureJobWorkItemScheduleActivity extends Activity {
    private static final String TAG = "CS_FIXTURE_JOB";
    /** Reserved range keeps repeated probes independent of a stale Host execution record. */
    public static final int JOB_ID_MIN = 1805;
    private static final int JOB_ID_RANGE = 1000;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String runId = "work-item-" + System.currentTimeMillis();
        int jobId = nextJobId();
        JobInfo job = new JobInfo.Builder(jobId,
                new ComponentName(this, FixtureJobService.class))
                // Give both enqueue transactions a bounded window before Host scheduling;
                // zero latency would allow the first item to run before the second arrives.
                .setMinimumLatency(1_000L)
                .setOverrideDeadline(10_000L)
                .build();
        JobScheduler scheduler = getSystemService(JobScheduler.class);
        // Make repeated device probes transactional: a previous Host force-stop may leave
        // the same virtual Job ID in RUNNING until its recovery callback is observed.
        // Cancel is routed through the virtual Job namespace and clears that stale execution
        // before the new enqueue transaction begins.
        if (scheduler != null) {
            scheduler.cancelAll();
            scheduler.cancel(jobId);
        }
        int firstResult = scheduler == null ? -1 : scheduler.enqueue(job,
                workItem(runId, "guest-work-payload-1"));
        int secondResult = scheduler == null ? -1 : scheduler.enqueue(job,
                workItem(runId, "guest-work-payload-2"));
        int result = firstResult == JobScheduler.RESULT_SUCCESS
                && secondResult == JobScheduler.RESULT_SUCCESS ? JobScheduler.RESULT_SUCCESS : -1;
        Log.i(TAG, "JOB_WORK_ENQUEUE_RESULT jobId=" + jobId + " result=" + result
                + " result1=" + firstResult + " result2=" + secondResult
                + " runId=" + runId + " pid=" + android.os.Process.myPid());
        setFinishOnTouchOutside(false);
    }

    public static boolean isWorkItemJob(int jobId) {
        return jobId >= JOB_ID_MIN && jobId < JOB_ID_MIN + JOB_ID_RANGE;
    }

    private static int nextJobId() {
        return JOB_ID_MIN + (int) (Math.abs(System.currentTimeMillis()) % JOB_ID_RANGE);
    }

    private JobWorkItem workItem(String runId, String payload) {
        Intent intent = new Intent("com.warden.controlledsandbox.fixture.WORK_ITEM");
        intent.setPackage(getPackageName());
        intent.putExtra("runId", runId);
        intent.putExtra("payload", payload);
        return new JobWorkItem(intent);
    }
}
