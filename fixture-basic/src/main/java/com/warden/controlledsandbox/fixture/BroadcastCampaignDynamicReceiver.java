package com.warden.controlledsandbox.fixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Activity-owned dynamic Receiver used to prove registration and stop cleanup. */
public final class BroadcastCampaignDynamicReceiver extends BroadcastReceiver {
    private static final String TAG = "CS_BROADCAST_CAMPAIGN";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && (context.getPackageName() + ".C1_T03_DYNAMIC")
                .equals(intent.getAction())) {
            Log.i(TAG, "C1_T03_DYNAMIC_RECEIVED package=" + context.getPackageName());
        }
    }
}
