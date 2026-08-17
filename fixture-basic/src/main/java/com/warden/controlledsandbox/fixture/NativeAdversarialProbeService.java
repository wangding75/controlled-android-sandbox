package com.warden.controlledsandbox.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** Test-only service used for in-sandbox Native campaign execution. */
public final class NativeAdversarialProbeService extends Service {
    private static final String TAG = "CS_NATIVE_ADV";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String context = intent == null ? "IN_SANDBOX"
                : intent.getStringExtra("cas.native.context");
        if (context == null || context.trim().isEmpty()) context = "IN_SANDBOX";
        Log.i(TAG, "SERVICE_BEGIN context=" + context + " files=" + getFilesDir());
        String result = NativeAdversarialProbe.run(getFilesDir(), context);
        Log.i(TAG, "SERVICE_DONE bytes=" + result.length());
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
