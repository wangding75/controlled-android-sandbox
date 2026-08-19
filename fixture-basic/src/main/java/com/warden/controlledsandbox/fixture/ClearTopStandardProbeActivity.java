package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Verifies CLEAR_TOP with a standard launchMode target:  the original target is finished and
 * re-created (a second onCreate) while the child sitting above it is removed.
 */
public final class ClearTopStandardProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final String RELAUNCH_FLAG = "clearTopStandardRelaunch";
    static int onCreateCount;
    static int onNewIntentCount;
    static int onDestroyCount;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        onCreateCount++;
        if (getIntent().getBooleanExtra(RELAUNCH_FLAG, false)) {
            if (onCreateCount == 2 && onNewIntentCount == 0 && onDestroyCount >= 1) {
                Log.i(TAG, "FRAMEWORK_PROBE_TASK_CLEAR_TOP_STANDARD_PASS create=" + onCreateCount
                        + " newIntent=" + onNewIntentCount + " destroy=" + onDestroyCount);
            } else {
                Log.e(TAG, "FRAMEWORK_PROBE_TASK_CLEAR_TOP_STANDARD_FAIL reason=BAD_COUNTS create="
                        + onCreateCount + " newIntent=" + onNewIntentCount
                        + " destroy=" + onDestroyCount);
            }
            finish();
            return;
        }
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_CLEAR_TOP_STANDARD_CREATE");
        Intent child = new Intent(this, DetailActivity.class);
        startActivity(child);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent clearTop = new Intent(this, ClearTopStandardProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(RELAUNCH_FLAG, true);
            startActivity(clearTop);
        }, 2500L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onNewIntentCount++;
        Log.e(TAG, "FRAMEWORK_PROBE_TASK_CLEAR_TOP_STANDARD_FAIL reason=UNEXPECTED_NEW_INTENT");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        onDestroyCount++;
    }
}