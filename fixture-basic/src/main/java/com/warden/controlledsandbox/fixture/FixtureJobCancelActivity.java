package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;

/** Schedules an ineligible job and cancels it before Host JobScheduler execution. */
public final class FixtureJobCancelActivity extends Activity {
    private static final String TAG = "CS_FIXTURE_JOB";
    public static final int JOB_ID = 1802;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String runId = "cancel-before-" + System.currentTimeMillis();
        PersistableBundle extras = new PersistableBundle();
        extras.putString("runId", runId);
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(this, FixtureJobService.class))
                .setMinimumLatency(120_000L)
                .setOverrideDeadline(120_000L)
                .setExtras(extras)
                .build();
        JobScheduler scheduler = getSystemService(JobScheduler.class);
        int schedule = scheduler == null ? -1 : scheduler.schedule(job);
        Log.i(TAG, "JOB_SCHEDULE_RESULT case=cancel-before jobId=" + JOB_ID
                + " result=" + schedule + " runId=" + runId);
        if (scheduler != null) scheduler.cancel(JOB_ID);
        Log.i(TAG, "JOB_CANCEL_RESULT case=cancel-before jobId=" + JOB_ID
                + " runId=" + runId + " pid=" + android.os.Process.myPid());
    }
}
