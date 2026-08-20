package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Verifies REORDER_TO_FRONT: brings an existing non-top instance to front with onNewIntent. */
public final class ReorderToFrontProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final String RELAUNCH_FLAG = "reorderRelaunch";
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
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_FAIL reason=SECOND_ON_CREATE");
            finish();
            return;
        }
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_CREATE");
        Intent child = new Intent(this, DetailActivity.class);
        startActivity(child);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent reorder = new Intent(this, ReorderToFrontProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(RELAUNCH_FLAG, true);
            startActivity(reorder);
        }, 2500L);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.i(TAG, "FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_COUNTS create=" + onCreateCount
                    + " newIntent=" + onNewIntentCount + " start=" + onStartCount
                    + " resume=" + onResumeCount + " stop=" + onStopCount);
            if (onNewIntentCount == 0 && onCreateCount == 1) {
                Log.e(TAG, "FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_FAIL reason=NO_NEW_INTENT");
            }
            // Do not finish here: the deferred onNewIntent verifier owns teardown.
        }, 12000L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onNewIntentCount++;
        if (!intent.getBooleanExtra(RELAUNCH_FLAG, false)) {
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_FAIL reason=EXTRA_MISSING");
            return;
        }
        // Do not finish inside onNewIntent: let the framework drive the real
        // STOPPED -> STARTED -> RESUMED transition to completion, then verify the record became
        // the resumed top from a post callback.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.i(TAG, "FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_LIFECYCLE create=" + onCreateCount
                    + " newIntent=" + onNewIntentCount + " start=" + onStartCount
                    + " resume=" + onResumeCount + " stop=" + onStopCount);
            TaskProbeEvidence.reorderToFront(this, onCreateCount, onNewIntentCount,
                    onStartCount, onResumeCount, onStopCount, onDestroyCount);
            TaskProbeEvidence.requestBackAfterEvidence(this, "reorder_to_front",
                    () -> new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1000L));
        }, 900L);
    }

    @Override protected void onStart() { super.onStart(); onStartCount++; }
    @Override protected void onResume() { super.onResume(); onResumeCount++; }
    @Override protected void onStop() {
        super.onStop();
        onStopCount++;
    }
    @Override protected void onDestroy() { super.onDestroy(); onDestroyCount++; }
}
