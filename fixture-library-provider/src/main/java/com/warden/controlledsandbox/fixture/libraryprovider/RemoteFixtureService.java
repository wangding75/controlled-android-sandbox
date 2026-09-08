package com.warden.controlledsandbox.fixture.libraryprovider;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** Exported only for the fixture's explicit cross-package Service contract. */
public final class RemoteFixtureService extends Service {
    @Override public void onCreate() {
        super.onCreate();
        Log.i("CS_P1_02_FIXTURE", "REMOTE_SERVICE_CREATE pid="
                + android.os.Process.myPid());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("CS_P1_02_FIXTURE", "REMOTE_SERVICE_START id=" + startId + " pid="
                + android.os.Process.myPid());
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
