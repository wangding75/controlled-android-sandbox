package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Finishes through the real framework Activity record with a typed result Intent. */
public final class FrameworkActivityResultChildActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String request = getIntent().getStringExtra("resultRequest");
        if (!"framework-result-value".equals(request)) {
            Log.e(TAG, "FRAMEWORK_PROBE_ACTIVITY_RESULT_CHILD_FAIL reason=REQUEST_MISSING");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        Intent result = new Intent()
                .setAction(getPackageName() + ".FRAMEWORK_ACTIVITY_RESULT")
                .putExtra("resultValue", request);
        setResult(RESULT_OK, result);
        Log.i(TAG, "FRAMEWORK_PROBE_ACTIVITY_RESULT_CHILD_FINISH resultCode=" + RESULT_OK);
        finish();
    }
}
