package com.warden.controlledsandbox;

import android.app.Application;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeDiagnostics;

public final class SandboxApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        RuntimeDiagnostics.install(this, "host");
    }
}
