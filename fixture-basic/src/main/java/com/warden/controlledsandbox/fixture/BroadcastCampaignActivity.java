package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Package-neutral Guest API campaign for C1-T03.  The host only launches this Activity and
 * records its logcat evidence; all broadcast calls originate from the Guest Context.
 */
public final class BroadcastCampaignActivity extends Activity {
    private static final String TAG = "CS_BROADCAST_CAMPAIGN";
    private static final String PREFIX = "com.warden.controlledsandbox.fixture.C1_T03_";
    private BroadcastCampaignDynamicReceiver dynamicReceiver;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dynamicReceiver = new BroadcastCampaignDynamicReceiver();
        IntentFilter filter = new IntentFilter(getPackageName() + ".C1_T03_DYNAMIC");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dynamicReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(dynamicReceiver, filter);
        }
        new Thread(this::runCampaign, "c1-t03-broadcast-campaign").start();
    }

    private void runCampaign() {
        boolean passed = false;
        try {
            Intent explicit = new Intent(PREFIX + "EXPLICIT")
                    .setComponent(new ComponentName(getPackageName(),
                            BroadcastCampaignHighReceiver.class.getName()))
                    .putExtra("campaignValue", "explicit-value");
            sendBroadcast(explicit);
            Thread.sleep(180L);

            sendBroadcast(new Intent(PREFIX + "IMPLICIT"));
            sendBroadcast(new Intent(PREFIX + "PERMISSION"), "android.permission.CAMERA");
            awaitOrdered(PREFIX + "ORDERED", 802, "low", false);
            awaitOrdered(PREFIX + "ABORT", 700, "initial", true);
            awaitOrdered(PREFIX + "ASYNC", 803, "async", false);
            awaitOrdered(PREFIX + "PERMISSION", 700, "initial", false,
                    "android.permission.BLUETOOTH_CONNECT");
            Log.i(TAG, "C1_T03_PERMISSION_DENIED_FILTERED");
            sendBroadcast(new Intent(getPackageName() + ".C1_T03_DYNAMIC"));
            Thread.sleep(250L);
            passed = true;
            Log.i(TAG, "C1_T03_BROADCAST_PASS");
        } catch (Throwable error) {
            Log.e(TAG, "C1_T03_BROADCAST_FAIL", error);
        } finally {
            final boolean result = passed;
            runOnUiThread(() -> {
                if (!result) Log.e(TAG, "C1_T03_BROADCAST_RUNTIME_FAIL");
                finish();
            });
        }
    }

    private void awaitOrdered(String action, int expectedCode, String expectedData,
                              boolean expectedAbort) throws Exception {
        awaitOrdered(action, expectedCode, expectedData, expectedAbort, null);
    }

    private void awaitOrdered(String action, int expectedCode, String expectedData,
                              boolean expectedAbort, String receiverPermission) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        final int[] code = {Integer.MIN_VALUE};
        final String[] data = {null};
        final boolean[] aborted = {false};
        BroadcastReceiver resultReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                code[0] = getResultCode();
                data[0] = getResultData();
                aborted[0] = getAbortBroadcast();
                completed.countDown();
            }
        };
        sendOrderedBroadcast(new Intent(action), receiverPermission, resultReceiver,
                new Handler(Looper.getMainLooper()), 700, "initial", new Bundle());
        if (!completed.await(15L, TimeUnit.SECONDS)) {
            throw new IllegalStateException("C1_T03_ORDERED_RESULT_TIMEOUT:" + action);
        }
        if (code[0] != expectedCode || !expectedData.equals(data[0]) || aborted[0] != expectedAbort) {
            throw new IllegalStateException("C1_T03_ORDERED_RESULT_MISMATCH:" + action
                    + ":code=" + code[0] + ":data=" + data[0] + ":aborted=" + aborted[0]);
        }
        Log.i(TAG, "C1_T03_ORDERED_RESULT_PASS action=" + action + " code=" + code[0]
                + " aborted=" + aborted[0]);
    }

    @Override protected void onDestroy() {
        if (dynamicReceiver != null) {
            try { unregisterReceiver(dynamicReceiver); } catch (RuntimeException ignored) { }
            dynamicReceiver = null;
        }
        super.onDestroy();
    }
}
