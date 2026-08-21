package com.warden.controlledsandbox.fixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Ordered async Receiver used to prove the one-shot PendingResult completion bridge. */
public final class BroadcastCampaignAsyncReceiver extends BroadcastReceiver {
    private static final String TAG = "CS_BROADCAST_CAMPAIGN";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"com.warden.controlledsandbox.fixture.C1_T03_ASYNC"
                .equals(intent.getAction())) return;
        final PendingResult pending = goAsync();
        Log.i(TAG, "C1_T03_ASYNC_RECEIVED");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Bundle extras = new Bundle();
            extras.putString("orderedStage", "async");
            pending.setResultCode(803);
            pending.setResultData("async");
            pending.setResultExtras(extras);
            try {
                pending.finish();
                Log.i(TAG, "C1_T03_ASYNC_FINISHED");
            } catch (Throwable error) {
                Log.e(TAG, "C1_T03_ASYNC_FINISH_FAILED", error);
            }
        }, 120L);
    }
}
