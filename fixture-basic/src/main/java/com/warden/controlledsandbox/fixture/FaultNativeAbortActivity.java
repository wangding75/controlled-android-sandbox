package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Native abort() probe. Do not catch the fatal signal. */
public final class FaultNativeAbortActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("CS_FAULT", "NATIVE_ABORT_BEGIN");
        FixtureNative.crash("abort");
    }
}
