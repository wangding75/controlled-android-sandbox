package com.warden.controlledsandbox.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/** P1-11: terminate a prepared explicit-process Guest with a real uncaught Java exception. */
public final class P111JavaCrashService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Finish the started Service before crashing: Android then cannot restart this fault
        // component in the recovered Guest generation and blur the first-failure evidence.
        stopSelf(startId);
        Thread crash = new Thread(() -> {
            SystemClock.sleep(100L);
            Log.i("CS_FAULT", "P1_11_JAVA_CRASH_THREAD_BEGIN");
            throw new RuntimeException("P1_11_JAVA_CRASH");
        }, "p1-11-java-crash");
        crash.start();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
