package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Verifies singleTask semantics with a real A(singleTask) -> B -> A transition: relaunching the
 * singleTask root after a child Activity is pushed must remove the child, reuse the original
 * ActivityRecord (no second onCreate), bring it back to top and deliver onNewIntent.
 */
public final class TaskSemanticsProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    static final String RELAUNCH_FLAG = "taskSemanticsRelaunch";
    static int onCreateCount;
    static int onNewIntentCount;
    static int onStartCount;
    static int onResumeCount;
    static int onStopCount;
    static int onDestroyCount;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        onCreateCount++;
        if (getIntent().getBooleanExtra(RELAUNCH_FLAG, false)) {
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_REUSE_FAIL reason=SECOND_ON_CREATE");
            finish();
            return;
        }
        if (onCreateCount > 1) {
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_REUSE_FAIL reason=MULTIPLE_CREATE");
            finish();
            return;
        }
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_CREATE");
        Intent child = new Intent(this, DetailActivity.class);
        startActivity(child);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // Relaunch the singleTask root after the child is on top.  The virtual ledger must
            // clear B above A, reuse A and deliver onNewIntent instead of creating a second A.
            Intent relaunch = new Intent(this, TaskSemanticsProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(RELAUNCH_FLAG, true);
            startActivity(relaunch);
        }, 2500L);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.i(TAG, "FRAMEWORK_PROBE_TASK_REUSE_COUNTS create=" + onCreateCount
                    + " newIntent=" + onNewIntentCount + " start=" + onStartCount
                    + " resume=" + onResumeCount);
            if (onNewIntentCount == 0 && onCreateCount == 1) {
                Log.e(TAG, "FRAMEWORK_PROBE_TASK_REUSE_FAIL reason=NO_NEW_INTENT");
            }
        }, 12000L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onNewIntentCount++;
        if (!intent.getBooleanExtra(RELAUNCH_FLAG, false)) {
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_REUSE_FAIL reason=EXTRA_MISSING");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.i(TAG, "FRAMEWORK_PROBE_TASK_REUSE_LIFECYCLE create=" + onCreateCount
                    + " newIntent=" + onNewIntentCount + " start=" + onStartCount
                    + " resume=" + onResumeCount);
            TaskProbeEvidence.singleTask(this, onCreateCount, onNewIntentCount,
                    onStartCount, onResumeCount, onStopCount, onDestroyCount);
            TaskProbeEvidence.requestBackAfterEvidence(this, "single_task",
                    () -> new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1000L));
        }, 900L);
    }

    @Override protected void onStart() { super.onStart(); onStartCount++; }
    @Override protected void onResume() { super.onResume(); onResumeCount++; }
    @Override protected void onStop() { super.onStop(); onStopCount++; }
    @Override protected void onDestroy() { super.onDestroy(); onDestroyCount++; }
}
