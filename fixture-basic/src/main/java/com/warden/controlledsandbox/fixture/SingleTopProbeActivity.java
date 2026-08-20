package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Verifies singleTop launchMode: reuse on top must deliver onNewIntent without a second onCreate. */
public final class SingleTopProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final String SECOND_LAUNCH = "singleTopSecondLaunch";
    static int onCreateCount;
    static int onNewIntentCount;
    static int onStartCount;
    static int onResumeCount;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        onCreateCount++;
        if (getIntent().getBooleanExtra(SECOND_LAUNCH, false)) {
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_FAIL reason=SECOND_ON_CREATE");
            finish();
            return;
        }
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_CREATE");
        Intent relaunch = new Intent(this, SingleTopProbeActivity.class)
                .putExtra(SECOND_LAUNCH, true);
        startActivity(relaunch);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.i(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_COUNTS create=" + onCreateCount
                    + " newIntent=" + onNewIntentCount + " start=" + onStartCount
                    + " resume=" + onResumeCount);
            boolean pass = onCreateCount == 1 && onNewIntentCount == 1;
            TaskProbeEvidence.singleTopTop(this, pass, onCreateCount, onNewIntentCount,
                    onStartCount, onResumeCount);
            if (!pass) {
                Log.e(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_FAIL reason=BAD_COUNTS");
            }
            finish();
        }, 1200L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onNewIntentCount++;
        if (!intent.getBooleanExtra(SECOND_LAUNCH, false)) {
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_FAIL reason=EXTRA_MISSING");
            return;
        }
        if (onCreateCount == 1 && onNewIntentCount == 1) {
            Log.i(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_PASS");
        }
    }

    @Override protected void onStart() { super.onStart(); onStartCount++; }
    @Override protected void onResume() { super.onResume(); onResumeCount++; }
}
