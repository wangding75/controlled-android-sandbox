package com.warden.controlledsandbox.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/** P1-11 native abort probe on the ordinary explicit-process Service path. */
public final class P111NativeAbortService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // See P111JavaCrashService: remove the deliberate fault service before its process
        // dies so the recovered generation is not immediately forced back into the same crash.
        stopSelf(startId);
        new Thread(() -> {
            SystemClock.sleep(100L);
            Log.i("CS_FAULT", "P1_11_NATIVE_ABORT_SERVICE_BEGIN");
            FixtureNative.crash("abort");
        }, "p1-11-native-abort").start();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
