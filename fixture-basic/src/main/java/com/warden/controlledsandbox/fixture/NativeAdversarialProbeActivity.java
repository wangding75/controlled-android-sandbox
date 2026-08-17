package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Exported test-only entry. Runs the adversarial Native campaign and exits. */
public final class NativeAdversarialProbeActivity extends Activity {
    private static final String TAG = "CS_NATIVE_ADV";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String context = getIntent() == null ? "DIRECT_FIXTURE"
                : getIntent().getStringExtra("cas.native.context");
        if (context == null || context.trim().isEmpty()) context = "DIRECT_FIXTURE";
        Log.i(TAG, "ACTIVITY_BEGIN context=" + context + " files=" + getFilesDir());
        String result = NativeAdversarialProbe.run(getFilesDir(), context);
        Log.i(TAG, "ACTIVITY_DONE bytes=" + result.length());
        finish();
    }
}
