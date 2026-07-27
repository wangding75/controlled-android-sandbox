package com.warden.controlledsandbox.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class FixtureService extends Service {
    private final FixtureBinder binder = new FixtureBinder();

    @Override public void onCreate() {
        super.onCreate();
        Log.i("CS_FIXTURE", "SERVICE_CREATE " + getClass().getName()
                + " process=" + android.app.Application.getProcessName());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("CS_FIXTURE", "SERVICE_START id=" + startId
                + " action=" + (intent == null ? "" : intent.getAction()));
        return START_STICKY;
    }
    @Override public void onDestroy() {
        Log.i("CS_FIXTURE", "SERVICE_DESTROY " + getClass().getName());
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) {
        Log.i("CS_FIXTURE", "SERVICE_BIND action=" + (intent == null ? "" : intent.getAction()));
        return binder;
    }
    @Override public boolean onUnbind(Intent intent) {
        Log.i("CS_FIXTURE", "SERVICE_UNBIND");
        return true;
    }
    @Override public void onRebind(Intent intent) {
        super.onRebind(intent);
        Log.i("CS_FIXTURE", "SERVICE_REBIND");
    }

    public static final class FixtureBinder extends Binder {
        public String ping() { return "BOUND_SERVICE_OK"; }
    }
}
