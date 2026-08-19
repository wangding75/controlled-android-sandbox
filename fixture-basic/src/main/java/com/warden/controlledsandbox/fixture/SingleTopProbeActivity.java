package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Verifies singleTop launchMode semantics (reuse on top receives onNewIntent). */
public final class SingleTopProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final String SECOND_LAUNCH = "singleTopSecondLaunch";
    private boolean newIntentReceived;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
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
            if (!newIntentReceived) {
                Log.e(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_FAIL reason=NO_NEW_INTENT");
            }
            finish();
        }, 1200L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!intent.getBooleanExtra(SECOND_LAUNCH, false)) {
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_FAIL reason=EXTRA_MISSING");
            return;
        }
        newIntentReceived = true;
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_SINGLETOP_PASS");
        finish();
    }
}
