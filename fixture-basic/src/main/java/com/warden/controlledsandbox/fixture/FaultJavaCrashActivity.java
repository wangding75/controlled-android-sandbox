package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.os.Bundle;

/** Guest Java uncaught-exception probe. Do not catch. */
public final class FaultJavaCrashActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        throw new RuntimeException("CAS_FAULT_JAVA_UNCAUGHT");
    }
}
