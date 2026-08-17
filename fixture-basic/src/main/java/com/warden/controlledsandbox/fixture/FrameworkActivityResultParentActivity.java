package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Exercises ActivityThread's real startActivityForResult/result dispatch path. */
public final class FrameworkActivityResultParentActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final int REQUEST_CODE = 701;
    private static final String RESULT_VALUE = "framework-result-value";
    private boolean callback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(TAG, "FRAMEWORK_PROBE_ACTIVITY_RESULT_PARENT_CREATE");
        Intent child = new Intent().setComponent(new ComponentName(
                getPackageName(), FrameworkActivityResultChildActivity.class.getName()))
                .setAction(getPackageName() + ".FRAMEWORK_ACTIVITY_RESULT_CHILD")
                .putExtra("resultRequest", RESULT_VALUE);
        Log.i(TAG, "FRAMEWORK_PROBE_ACTIVITY_RESULT_START requestCode=" + REQUEST_CODE);
        startActivityForResult(child, REQUEST_CODE);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!callback) {
                Log.e(TAG, "FRAMEWORK_PROBE_ACTIVITY_RESULT_FAIL reason=CALLBACK_TIMEOUT");
                finish();
            }
        }, 3000L);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        String value = data == null ? "" : data.getStringExtra("resultValue");
        if (requestCode != REQUEST_CODE || resultCode != RESULT_OK
                || !RESULT_VALUE.equals(value)) {
            Log.e(TAG, "FRAMEWORK_PROBE_ACTIVITY_RESULT_FAIL requestCode=" + requestCode
                    + " resultCode=" + resultCode + " value=" + value);
            finish();
            return;
        }
        callback = true;
        Log.i(TAG, "FRAMEWORK_PROBE_ACTIVITY_RESULT_PASS requestCode=" + requestCode
                + " resultCode=" + resultCode + " value=" + value);
        finish();
    }
}
