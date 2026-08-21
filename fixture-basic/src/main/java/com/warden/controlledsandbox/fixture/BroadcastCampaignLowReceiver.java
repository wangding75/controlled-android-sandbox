package com.warden.controlledsandbox.fixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Lower-priority manifest Receiver used to prove ordered result propagation and abort. */
public final class BroadcastCampaignLowReceiver extends BroadcastReceiver {
    private static final String TAG = "CS_BROADCAST_CAMPAIGN";
    private static final String PREFIX = "com.warden.controlledsandbox.fixture.C1_T03_";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if ((PREFIX + "IMPLICIT").equals(action)) {
            Log.i(TAG, "C1_T03_IMPLICIT_LOW_RECEIVED");
        } else if ((PREFIX + "ORDERED").equals(action)) {
            if (getResultCode() != 801 || !"high".equals(getResultData())
                    || getResultExtras(false) == null
                    || !"high".equals(getResultExtras(false).getString("orderedStage"))) {
                throw new IllegalStateException("C1_T03_ORDERED_CHAIN_MISMATCH");
            }
            Bundle extras = new Bundle();
            extras.putString("orderedStage", "low");
            setResultCode(802);
            setResultData("low");
            setResultExtras(extras);
            Log.i(TAG, "C1_T03_ORDERED_LOW_RECEIVED");
        } else if ((PREFIX + "ABORT").equals(action)) {
            Log.e(TAG, "C1_T03_ABORT_LOW_UNEXPECTED");
        } else if ((PREFIX + "PERMISSION").equals(action)) {
            Log.i(TAG, "C1_T03_PERMISSION_LOW_RECEIVED");
        }
    }
}
