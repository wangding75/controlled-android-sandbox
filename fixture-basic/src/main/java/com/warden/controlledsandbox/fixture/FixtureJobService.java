package com.warden.controlledsandbox.fixture;

import android.app.job.JobParameters;
import android.app.job.JobService;
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
