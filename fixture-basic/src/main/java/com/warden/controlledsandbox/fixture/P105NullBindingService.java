package com.warden.controlledsandbox.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** Deliberately returns no Binder so P1-05 observes the framework null-binding callback. */
public final class P105NullBindingService extends Service {
    @Override public IBinder onBind(Intent intent) {
        Log.i("CS_P1_05_FIXTURE", "P1_05_NULL_SERVICE_ON_BIND");
        return null;
    }
}
