package com.warden.controlledsandbox.runtime.broker;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Service-shaped host relay for AMS-held Service PendingIntents. */
public final class RuntimePendingIntentRelayService extends Service {
    public static final String CLASS_NAME = RuntimePendingIntentRelayService.class.getName();

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        RuntimePendingIntentRelayReceiver.dispatchToBroker(this, intent);
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
