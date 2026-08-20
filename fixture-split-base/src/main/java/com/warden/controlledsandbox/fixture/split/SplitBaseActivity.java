package com.warden.controlledsandbox.fixture.split;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Base component whose class loader probes a class that exists only in the feature split. */
public final class SplitBaseActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean featureLoaded = false;
        try {
            Class.forName("com.warden.controlledsandbox.fixture.split.feature.FeatureMarker",
                    true, getClassLoader());
            featureLoaded = true;
        } catch (ClassNotFoundException ignored) {
            // The CAS smoke records the result through logcat; absence is a split-loader failure.
        }
        Log.i("CS_SPLIT_FIXTURE", "BASE_CREATE featureClassLoaded=" + featureLoaded);
    }
}
