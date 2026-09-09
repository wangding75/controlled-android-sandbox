package com.warden.controlledsandbox;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/** Debug-only delayed bind endpoint used to prove late-callback fencing. */
public final class P111LateConnectionService extends Service {
    private static final String TAG = "CS_P1_11";
    private final IBinder binder = new Binder();

    @Override public IBinder onBind(Intent intent) {
        boolean delayed = intent != null && intent.getBooleanExtra("p111.late", false);
        if (delayed) SystemClock.sleep(300L);
        Log.i(TAG, "P1_11_LATE_BIND_CALLBACK delayed=" + delayed);
        return binder;
    }
}
