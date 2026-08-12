package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;

/** Schedules one immediately eligible job and leaves callback evidence in logcat/files. */
public final class FixtureJobScheduleActivity extends Activity {
    private static final String TAG = "CS_FIXTURE_JOB";
    public static final int JOB_ID = 1801;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String runId = "schedule-" + System.currentTimeMillis();
        PersistableBundle extras = new PersistableBundle();
        extras.putString("runId", runId);
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(this, FixtureJobService.class))
                .setMinimumLatency(0L)
                .setOverrideDeadline(10_000L)
                .setExtras(extras)
                .build();
        JobScheduler scheduler = getSystemService(JobScheduler.class);
        int result = scheduler == null ? -1 : scheduler.schedule(job);
        Log.i(TAG, "JOB_SCHEDULE_RESULT case=schedule jobId=" + JOB_ID
                + " result=" + result + " runId=" + runId + " pid="
                + android.os.Process.myPid() + " files=" + getFilesDir());
        setFinishOnTouchOutside(false);
    }
}
