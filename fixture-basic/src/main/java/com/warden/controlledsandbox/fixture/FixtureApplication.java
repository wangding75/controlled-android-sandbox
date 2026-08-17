package com.warden.controlledsandbox.fixture;

import android.app.Application;
import android.content.Context;
import android.util.Log;

public final class FixtureApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        int uid = android.os.Process.myUid();
        if (uid >= 99000 && uid <= 99999) {
            Log.i("CS_FIXTURE", "APPLICATION_CREATE isolated uid=" + uid
                    + " package=" + getPackageName());
            return;
        }
        int count = getSharedPreferences("fixture", Context.MODE_PRIVATE).getInt("applicationCreates", 0) + 1;
        getSharedPreferences("fixture", Context.MODE_PRIVATE).edit().putInt("applicationCreates", count).commit();
        Log.i("CS_FIXTURE", "APPLICATION_CREATE count=" + count + " package=" + getPackageName()
                + " process=" + getApplicationInfo().processName);
        Log.i("CS_FIXTURE", "NATIVE_LOAD " + FixtureNative.loadStatus());
    }
}
