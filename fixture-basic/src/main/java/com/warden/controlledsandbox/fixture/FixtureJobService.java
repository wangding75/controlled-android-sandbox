package com.warden.controlledsandbox.fixture;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.app.job.JobWorkItem;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Minimal Guest JobService used to exercise the real host callback bridge. */
public final class FixtureJobService extends JobService {
    private static final String TAG = "CS_FIXTURE_JOB";

    @Override public boolean onStartJob(JobParameters parameters) {
        String runId = parameters.getExtras() == null
                ? "" : parameters.getExtras().getString("runId", "");
        int jobId = parameters.getJobId();
        if (FixtureJobWorkItemScheduleActivity.isWorkItemJob(jobId)) {
            return onStartWorkItemJob(parameters);
        }
        Log.i(TAG, "JOB_ON_START jobId=" + jobId + " runId=" + runId
                + " pid=" + android.os.Process.myPid() + " files=" + getFilesDir());
        write("job-callback-" + jobId + ".json", "{\"event\":\"onStartJob\",\"jobId\":"
                + jobId + ",\"runId\":\"" + escape(runId) + "\",\"pid\":"
                + android.os.Process.myPid() + "}\n");
        new Handler(Looper.getMainLooper()).post(() -> {
            Log.i(TAG, "JOB_FINISHED jobId=" + jobId + " runId=" + runId);
            jobFinished(parameters, false);
        });
        return true;
    }

    private boolean onStartWorkItemJob(JobParameters parameters) {
        try {
            int completedCount = 0;
            String runId = null;
            while (true) {
                JobWorkItem item = parameters.dequeueWork();
                if (item == null) break;
                if (item.getIntent() == null) {
                    Log.e(TAG, "JOB_WORK_ITEM_FAILED jobId=" + parameters.getJobId()
                            + " reason=DEQUEUE_NULL index=" + (completedCount + 1));
                    return false;
                }
                String action = item.getIntent().getAction();
                String itemPayload = item.getIntent().getStringExtra("payload");
                String itemRunId = item.getIntent().getStringExtra("runId");
                String expectedPayload = "guest-work-payload-" + (completedCount + 1);
                if (!"com.warden.controlledsandbox.fixture.WORK_ITEM".equals(action)
                        || !expectedPayload.equals(itemPayload) || itemRunId == null
                        || (runId != null && !runId.equals(itemRunId))) {
                    Log.e(TAG, "JOB_WORK_ITEM_FAILED jobId=" + parameters.getJobId()
                            + " reason=PAYLOAD_MISMATCH action=" + action
                            + " index=" + (completedCount + 1));
                    return false;
                }
                parameters.completeWork(item);
                runId = itemRunId;
                completedCount++;
                Log.i(TAG, "JOB_WORK_ITEM_COMPLETED jobId=" + parameters.getJobId()
                        + " index=" + completedCount + " runId=" + runId
                        + " deliveryCount=" + item.getDeliveryCount()
                        + " pid=" + android.os.Process.myPid());
            }
            if (completedCount != 2) {
                Log.e(TAG, "JOB_WORK_ITEM_FAILED jobId=" + parameters.getJobId()
                        + " reason=COUNT_MISMATCH count=" + completedCount);
                return false;
            }
            Log.i(TAG, "JOB_WORK_ITEMS_DRAINED jobId=" + parameters.getJobId()
                    + " count=" + completedCount + " runId=" + runId
                    + " pid=" + android.os.Process.myPid());
            jobFinished(parameters, false);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "JOB_WORK_ITEM_FAILED jobId=" + parameters.getJobId(), error);
            return false;
        }
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        String runId = parameters.getExtras() == null
                ? "" : parameters.getExtras().getString("runId", "");
        int jobId = parameters.getJobId();
        Log.i(TAG, "JOB_ON_STOP jobId=" + jobId + " runId=" + runId
                + " pid=" + android.os.Process.myPid());
        write("job-stop-" + jobId + ".json", "{\"event\":\"onStopJob\",\"jobId\":"
                + jobId + ",\"runId\":\"" + escape(runId) + "\",\"pid\":"
                + android.os.Process.myPid() + "}\n");
        return false;
    }

    private void write(String name, String value) {
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), name))) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            Log.e(TAG, "JOB_EVIDENCE_WRITE_FAILED name=" + name, error);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
