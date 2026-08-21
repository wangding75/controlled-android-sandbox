package com.warden.controlledsandbox.fixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** High-priority manifest Receiver used by the package-neutral C1-T03 campaign. */
public final class BroadcastCampaignHighReceiver extends BroadcastReceiver {
    private static final String TAG = "CS_BROADCAST_CAMPAIGN";
    private static final String PREFIX = "com.warden.controlledsandbox.fixture.C1_T03_";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if ((PREFIX + "EXPLICIT").equals(action)) {
            if (!"explicit-value".equals(intent.getStringExtra("campaignValue"))) {
                throw new IllegalStateException("C1_T03_EXPLICIT_EXTRA_MISMATCH");
            }
            Log.i(TAG, "C1_T03_EXPLICIT_RECEIVED package=" + context.getPackageName());
        } else if ((PREFIX + "IMPLICIT").equals(action)) {
            Log.i(TAG, "C1_T03_IMPLICIT_HIGH_RECEIVED");
        } else if ((PREFIX + "ORDERED").equals(action)) {
            if (getResultCode() != 700 || !"initial".equals(getResultData())) {
                throw new IllegalStateException("C1_T03_ORDERED_INITIAL_RESULT_MISMATCH");
            }
            Bundle extras = new Bundle();
            extras.putString("orderedStage", "high");
            setResultCode(801);
            setResultData("high");
            setResultExtras(extras);
            Log.i(TAG, "C1_T03_ORDERED_HIGH_RECEIVED");
        } else if ((PREFIX + "ABORT").equals(action)) {
            abortBroadcast();
            Log.i(TAG, "C1_T03_ABORT_HIGH_RECEIVED");
        } else if ((PREFIX + "PERMISSION").equals(action)) {
            Log.i(TAG, "C1_T03_PERMISSION_HIGH_RECEIVED");
        }
    }
}
