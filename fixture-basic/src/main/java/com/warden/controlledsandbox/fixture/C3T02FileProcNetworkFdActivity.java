package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Exported test-only entry for the C3-T02 campaign. */
public final class C3T02FileProcNetworkFdActivity extends Activity {
    private static final String TAG = "CS_C3_T02";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new android.view.View(this));
        String context = getIntent() == null ? "DIRECT_FIXTURE"
                : getIntent().getStringExtra("cas.native.context");
        if (context == null || context.trim().isEmpty()) context = "DIRECT_FIXTURE";
        final String executionContext = context;
        Log.i(TAG, "ACTIVITY_READY context=" + executionContext + " files=" + getFilesDir());
        new Thread(() -> {
            try {
                String result = C3T02FileProcNetworkFdProbe.run(getFilesDir(), executionContext);
                runOnUiThread(() -> {
                    Log.i(TAG, "ACTIVITY_DONE bytes=" + result.length());
                    finish();
                });
            } catch (Throwable error) {
                Log.e(TAG, "ACTIVITY_FAILED", error);
                runOnUiThread(this::finish);
            }
        }, "c3-t02-probe").start();
    }
}
