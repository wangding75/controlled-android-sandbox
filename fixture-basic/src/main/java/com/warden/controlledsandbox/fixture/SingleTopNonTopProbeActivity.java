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

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        onCreateCount++;
        if (getIntent().getBooleanExtra(RELAUNCH_FLAG, false)) {
            if (onCreateCount == 2 && onNewIntentCount == 0) {
                Log.i(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_NONTOP_PASS create=" + onCreateCount
                        + " newIntent=" + onNewIntentCount);
            } else {
                Log.e(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_NONTOP_FAIL reason=BAD_COUNTS create="
                        + onCreateCount + " newIntent=" + onNewIntentCount);
            }
            finish();
            return;
        }
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_NONTOP_CREATE");
        Intent child = new Intent(this, DetailActivity.class);
        startActivity(child);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent relaunch = new Intent(this, SingleTopNonTopProbeActivity.class)
                    .putExtra(RELAUNCH_FLAG, true);
            startActivity(relaunch);
        }, 2500L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onNewIntentCount++;
        Log.e(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_NONTOP_FAIL reason=UNEXPECTED_NEW_INTENT");
    }
}