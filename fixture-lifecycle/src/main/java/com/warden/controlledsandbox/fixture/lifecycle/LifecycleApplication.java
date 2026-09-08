package com.warden.controlledsandbox.fixture.lifecycle;

import android.app.Application;
import android.content.Context;

public final class LifecycleApplication extends Application {
    @Override protected void attachBaseContext(Context base) {
        LifecycleProbe.record("application.attach");
        super.attachBaseContext(base);
    }

    @Override public void onCreate() {
        LifecycleProbe.record("application.onCreate");
        super.onCreate();
    }
}
