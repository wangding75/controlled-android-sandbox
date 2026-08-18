package com.warden.controlledsandbox.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** Keeps the guest stub process alive after SystemHolder activity is backgrounded. */
public final class SystemHolderKeepAliveService extends Service {
    private static final String TAG = "CS_PI_HOLDER";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "KEEP_ALIVE pid=" + android.os.Process.myPid());
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
