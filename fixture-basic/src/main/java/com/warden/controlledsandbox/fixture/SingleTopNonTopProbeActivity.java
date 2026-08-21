package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Verifies singleTop non-top semantics:  a singleTop Activity that is NOT the task top must be
 * re-instantiated (a second onCreate) instead of receiving onNewIntent.
 */
public final class SingleTopNonTopProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final String RELAUNCH_FLAG = "singleTopNonTopRelaunch";
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
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                TaskProbeEvidence.singleTopNonTop(this, onCreateCount, onNewIntentCount,
                        onStartCount, onResumeCount, onStopCount, onDestroyCount);
                TaskProbeEvidence.requestBackAfterEvidence(this, "single_top_non_top",
                        () -> new Handler(Looper.getMainLooper()).postDelayed(this::finish, 200L));
            }, 600L);
            return;
        }
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_NONTOP_CREATE");
        Intent child = new Intent(this, DetailActivity.class);
        startActivity(child);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent relaunch = new Intent(this, SingleTopNonTopProbeActivity.class)
                    .putExtra(RELAUNCH_FLAG, true);
            startActivity(relaunch);
        }, 1200L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onNewIntentCount++;
        Log.e(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_NONTOP_FAIL reason=UNEXPECTED_NEW_INTENT");
    }
    @Override protected void onStart() { super.onStart(); onStartCount++; }
    @Override protected void onResume() { super.onResume(); onResumeCount++; }
    @Override protected void onStop() { super.onStop(); onStopCount++; }
    @Override protected void onDestroy() { super.onDestroy(); onDestroyCount++; }
}
