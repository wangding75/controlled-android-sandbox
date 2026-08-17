package com.warden.controlledsandbox.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** Service start stall long enough to be an ANR. Do not shorten to avoid ANR. */
public final class FaultAnrService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("CS_FAULT", "ANR_SERVICE_BEGIN");
        try {
            Thread.sleep(25_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Log.i("CS_FAULT", "ANR_SERVICE_END");
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
