package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Verifies standard launchMode semantics (new instance receives onCreate). */
public final class StandardTaskProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final String SECOND_LAUNCH = "standardSecondLaunch";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra(SECOND_LAUNCH, false)) {
            Log.i(TAG, "FRAMEWORK_PROBE_TASK_STANDARD_PASS");
            finish();
            return;
        }
        Log.i(TAG, "FRAMEWORK_PROBE_TASK_STANDARD_FIRST_CREATE");
        Intent relaunch = new Intent(this, StandardTaskProbeActivity.class)
                .putExtra(SECOND_LAUNCH, true);
        startActivity(relaunch);
    }
}
