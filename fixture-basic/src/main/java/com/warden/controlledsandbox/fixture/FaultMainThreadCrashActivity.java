package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.os.Bundle;

/** Guest main-thread crash after resume. Do not catch. */
public final class FaultMainThreadCrashActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override protected void onResume() {
        super.onResume();
        throw new RuntimeException("CAS_FAULT_MAIN_THREAD_CRASH");
    }
}
