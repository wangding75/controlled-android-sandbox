package com.warden.controlledsandbox.fixture;

import android.app.Application;
import android.content.Context;
import android.util.Log;

public final class FixtureApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        int count = getSharedPreferences("fixture", Context.MODE_PRIVATE).getInt("applicationCreates", 0) + 1;
        getSharedPreferences("fixture", Context.MODE_PRIVATE).edit().putInt("applicationCreates", count).commit();
        Log.i("CS_FIXTURE", "APPLICATION_CREATE count=" + count + " package=" + getPackageName()
                + " process=" + getApplicationInfo().processName);
        Log.i("CS_FIXTURE", "NATIVE_LOAD " + FixtureNative.loadStatus());
    }
}
