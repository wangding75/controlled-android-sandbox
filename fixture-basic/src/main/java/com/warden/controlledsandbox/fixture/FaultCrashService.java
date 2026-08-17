package com.warden.controlledsandbox.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Guest Service crash on start. Do not catch. */
public final class FaultCrashService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        throw new RuntimeException("CAS_FAULT_SERVICE_CRASH");
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
