package com.warden.controlledsandbox.runtime.component.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Fallback host declaration for a service lease.
 *
 * <p>GuestServiceFrameworkBridge consumes the ActivityThread CREATE_SERVICE message before the
 * framework invokes this implementation. Keeping a harmless fallback makes the declaration
 * total on OEM builds where a Handler callback cannot be installed.</p>
 */
public abstract class BaseStubService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }
}
