package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Verifies CLEAR_TOP with singleTop: clears the child and reuses the target via onNewIntent. */
public final class ClearTopProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final String RELAUNCH_FLAG = "clearTopRelaunch";
    static int onCreateCount;
    static int onNewIntentCount;
    static int onStartCount;
    static int onResumeCount;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        onCreateCount++;
        if (getIntent().getBooleanExtra(RELAUNCH_FLAG, false)) {
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_CLEAR_TOP_FAIL reason=SECOND_ON_CREATE");
            finish();
            return;
        }
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_CLEAR_TOP_CREATE");
        Intent child = new Intent(this, DetailActivity.class);
        startActivity(child);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent clearTop = new Intent(this, ClearTopProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(RELAUNCH_FLAG, true);
            startActivity(clearTop);
        }, 1500L);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.i(TAG, "FRAMEWORK_PROBE_TASK_CLEAR_TOP_COUNTS create=" + onCreateCount
                    + " newIntent=" + onNewIntentCount + " start=" + onStartCount
                    + " resume=" + onResumeCount);
            if (onNewIntentCount == 0 && onCreateCount == 1) {
                Log.e(TAG, "FRAMEWORK_PROBE_TASK_CLEAR_TOP_FAIL reason=NO_NEW_INTENT");
            }
            finish();
        }, 5000L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onNewIntentCount++;
        if (!intent.getBooleanExtra(RELAUNCH_FLAG, false)) {
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_CLEAR_TOP_FAIL reason=EXTRA_MISSING");
            return;
        }
        if (onCreateCount == 1 && onNewIntentCount == 1) {
            Log.i(TAG, "FRAMEWORK_PROBE_TASK_CLEAR_TOP_PASS");
        } else {
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_CLEAR_TOP_FAIL reason=BAD_COUNTS");
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.i(TAG, "FRAMEWORK_PROBE_TASK_CLEAR_TOP_LIFECYCLE create=" + onCreateCount
                    + " newIntent=" + onNewIntentCount + " start=" + onStartCount
                    + " resume=" + onResumeCount);
            if (onResumeCount < 2 || onStartCount < 2) {
                Log.e(TAG, "FRAMEWORK_PROBE_TASK_CLEAR_TOP_FAIL reason=NOT_RESUMED");
            }
            finish();
        }, 900L);
    }

    @Override protected void onStart() { super.onStart(); onStartCount++; }
    @Override protected void onResume() { super.onResume(); onResumeCount++; }
}