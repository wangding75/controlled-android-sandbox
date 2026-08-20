package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Verifies standard launchMode semantics (two distinct instances each receive onCreate). */
public final class StandardTaskProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final String SECOND_LAUNCH = "standardSecondLaunch";
    static int onCreateCount;
    static int onStartCount;
    static int onResumeCount;
    static boolean backReturnedToFirst;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        onCreateCount++;
        if (getIntent().getBooleanExtra(SECOND_LAUNCH, false)) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                // The second real ActivityRecord must be removed by the framework Back path,
                // revealing the original record in the same task.
                onBackPressed();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    boolean pass = onCreateCount == 2 && backReturnedToFirst;
                    TaskProbeEvidence.standard(this, pass, onCreateCount, onStartCount,
                            onResumeCount);
                    Log.i(TAG, "FRAMEWORK_PROBE_TASK_STANDARD_COUNTS create=" + onCreateCount
                            + " start=" + onStartCount + " resume=" + onResumeCount
                            + " backReturned=" + backReturnedToFirst);
                    if (pass) Log.i(TAG, "FRAMEWORK_PROBE_TASK_STANDARD_PASS");
                    else Log.e(TAG, "FRAMEWORK_PROBE_TASK_STANDARD_FAIL reason=BACK_STACK");
                    finish();
                }, 400L);
            }, 250L);
            return;
        }
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_STANDARD_FIRST_CREATE");
        Intent relaunch = new Intent(this, StandardTaskProbeActivity.class)
                .putExtra(SECOND_LAUNCH, true);
        startActivity(relaunch);
    }

    @Override protected void onStart() { super.onStart(); onStartCount++; }
    @Override protected void onResume() {
        super.onResume();
        onResumeCount++;
        if (onCreateCount >= 2 && !getIntent().getBooleanExtra(SECOND_LAUNCH, false)) {
            backReturnedToFirst = true;
        }
    }
}
