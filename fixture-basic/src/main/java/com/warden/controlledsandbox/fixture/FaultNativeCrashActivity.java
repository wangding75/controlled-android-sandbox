package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Native SIGSEGV / abort probe. Do not catch the fatal signal. */
public final class FaultNativeCrashActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String mode = getIntent() == null ? "segv" : getIntent().getStringExtra("mode");
        if (mode == null || mode.trim().isEmpty()) mode = "segv";
        Log.i("CS_FAULT", "NATIVE_CRASH_BEGIN mode=" + mode);
        FixtureNative.crash(mode);
    }
}
