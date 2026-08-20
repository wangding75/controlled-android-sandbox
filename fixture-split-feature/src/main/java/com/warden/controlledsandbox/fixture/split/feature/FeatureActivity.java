package com.warden.controlledsandbox.fixture.split.feature;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Component declared and defined only in the feature split. */
public final class FeatureActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i("CS_SPLIT_FIXTURE", "FEATURE_CREATE classLoaded=true");
    }
}
