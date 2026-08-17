package com.warden.controlledsandbox.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** Isolated-process native fatal-signal probe. */
public final class IsolatedFaultNativeService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String mode = intent == null ? "segv" : intent.getStringExtra("mode");
        if (mode == null || mode.trim().isEmpty()) mode = "segv";
        Log.i("CS_FAULT", "ISOLATED_NATIVE_CRASH_BEGIN mode=" + mode);
        try {
            FixtureNative.crash(mode);
        } catch (Throwable error) {
            Log.e("CS_FAULT", "ISOLATED_NATIVE_LIB_UNAVAILABLE", error);
            android.os.Process.killProcess(android.os.Process.myPid());
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
