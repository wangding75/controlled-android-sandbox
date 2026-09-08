package com.warden.controlledsandbox.fixture.lifecycle;

import android.app.Application;

/** P1-04's explicit first-error fixture; it is present only in the v2 test artifact. */
public final class FailingLifecycleApplication extends Application {
    @Override public void onCreate() {
        LifecycleProbe.record("application.expectedFailure");
        throw new IllegalStateException("P1_04_EXPECTED_APPLICATION_ONCREATE_FAILURE");
    }
}
