package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;

/** Schedules a delayed job so a session stop/rebuild can test stale callback ownership. */
public final class FixtureJobDelayedActivity extends Activity {
    private static final String TAG = "CS_FIXTURE_JOB";
    public static final int JOB_ID = 1803;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String runId = "rebuild-old-" + System.currentTimeMillis();
        PersistableBundle extras = new PersistableBundle();
        extras.putString("runId", runId);
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(this, FixtureJobService.class))
                .setMinimumLatency(60_000L)
                .setOverrideDeadline(60_000L)
                .setExtras(extras)
                .build();
        JobScheduler scheduler = getSystemService(JobScheduler.class);
        int result = scheduler == null ? -1 : scheduler.schedule(job);
        Log.i(TAG, "JOB_SCHEDULE_RESULT case=session-rebuild jobId=" + JOB_ID
                + " result=" + result + " runId=" + runId + " pid="
                + android.os.Process.myPid());
    }
}
