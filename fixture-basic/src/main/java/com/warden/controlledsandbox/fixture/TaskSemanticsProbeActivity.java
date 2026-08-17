package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Exercises a real framework reuse edge instead of only checking PackageManager metadata. */
public final class TaskSemanticsProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final String SECOND_LAUNCH = "taskSemanticsSecondLaunch";
    private boolean newIntentReceived;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra(SECOND_LAUNCH, false)) {
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_REUSE_FAIL reason=SECOND_ON_CREATE");
            finish();
            return;
        }
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_CREATE");
        Intent relaunch = new Intent(this, TaskSemanticsProbeActivity.class)
                .setAction(getPackageName() + ".TASK_REUSE")
                .putExtra(SECOND_LAUNCH, true);
        startActivity(relaunch);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!newIntentReceived) {
                Log.e(TAG, "FRAMEWORK_PROBE_TASK_REUSE_FAIL reason=NO_NEW_INTENT");
            }
            finish();
        }, 1200L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!intent.getBooleanExtra(SECOND_LAUNCH, false)) {
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_REUSE_FAIL reason=EXTRA_MISSING");
            return;
        }
        newIntentReceived = true;
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_REUSE_PASS action=" + intent.getAction()
                + " component=" + String.valueOf(intent.getComponent()));
        finish();
    }
}
