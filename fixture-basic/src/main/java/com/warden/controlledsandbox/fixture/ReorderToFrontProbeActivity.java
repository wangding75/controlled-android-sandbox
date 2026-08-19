package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Verifies REORDER_TO_FRONT semantics (brings existing instance to front with onNewIntent). */
public final class ReorderToFrontProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final String RELAUNCH_FLAG = "reorderRelaunch";
    private boolean newIntentReceived;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        }, 1500L);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!newIntentReceived) {
                Log.e(TAG, "FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_FAIL reason=NO_NEW_INTENT");
            }
            finish();
        }, 5000L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!intent.getBooleanExtra(RELAUNCH_FLAG, false)) {
            Log.e(TAG, "FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_FAIL reason=EXTRA_MISSING");
            return;
        }
        newIntentReceived = true;
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_PASS");
        finish();
    }
}
