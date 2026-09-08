package com.warden.controlledsandbox.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

/** Dedicated-process Service used only to make a real disconnect/binding-died callback visible. */
public final class P105DyingService extends Service {
    @Override public IBinder onBind(Intent intent) {
        Log.i("CS_P1_05_FIXTURE", "P1_05_DYING_SERVICE_ON_BIND pid=" + Process.myPid());
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.i("CS_P1_05_FIXTURE", "P1_05_DYING_SERVICE_KILL pid=" + Process.myPid());
            Process.killProcess(Process.myPid());
        }, 700L);
        return new Binder();
    }
}
