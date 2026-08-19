package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Verifies standard launchMode semantics (two distinct instances each receive onCreate). */
public final class StandardTaskProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final String SECOND_LAUNCH = "standardSecondLaunch";
    static int onCreateCount;
    static int onStartCount;
    static int onResumeCount;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        onCreateCount++;
        if (getIntent().getBooleanExtra(SECOND_LAUNCH, false)) {
            Log.i(TAG, "FRAMEWORK_PROBE_TASK_STANDARD_COUNTS create=" + onCreateCount
                    + " start=" + onStartCount + " resume=" + onResumeCount);
            Log.i(TAG, "FRAMEWORK_PROBE_TASK_STANDARD_PASS");
            finish();
            return;
        }
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_STANDARD_FIRST_CREATE");
        Intent relaunch = new Intent(this, StandardTaskProbeActivity.class)
                .putExtra(SECOND_LAUNCH, true);
        startActivity(relaunch);
    }

    @Override protected void onStart() { super.onStart(); onStartCount++; }
    @Override protected void onResume() { super.onResume(); onResumeCount++; }
}