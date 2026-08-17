package com.warden.controlledsandbox.fixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public final class FixtureReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int count = context.getSharedPreferences("fixture", Context.MODE_PRIVATE).getInt("receiverCount", 0) + 1;
        context.getSharedPreferences("fixture", Context.MODE_PRIVATE).edit().putInt("receiverCount", count).commit();
        Log.i("CS_FIXTURE", "RECEIVER count=" + count + " action=" + (intent == null ? "" : intent.getAction()));
        if (intent != null && "com.warden.controlledsandbox.fixture32.PING".equals(intent.getAction())) {
            Log.i("CS_FIXTURE", "FRAMEWORK_PROBE_CROSS_PENDING_INTENT_RECEIVED");
        }
        if (intent != null
                && "com.warden.controlledsandbox.fixture.FRAMEWORK_RECEIVER_PROBE".equals(
                        intent.getAction())) {
            if (!"com.warden.controlledsandbox.fixture".equals(context.getPackageName())
                    || !"activity-thread-receiver".equals(
                            intent.getStringExtra("frameworkReceiverValue"))) {
                throw new IllegalStateException("FRAMEWORK_RECEIVER_CONTEXT_OR_EXTRA_MISMATCH");
            }
            Log.i("CS_FIXTURE", "FRAMEWORK_PROBE_RECEIVER_FRAMEWORK_PASS package="
                    + context.getPackageName());
        }
        if (intent != null && "com.warden.controlledsandbox.fixture32.DIRECT_FRAMEWORK_RECEIVER_PROBE"
                .equals(intent.getAction())) {
            if (!"com.warden.controlledsandbox.fixture32".equals(context.getPackageName())
                    || !"cross-package-activity-thread".equals(
                            intent.getStringExtra("frameworkReceiverValue"))) {
                throw new IllegalStateException("CROSS_FRAMEWORK_RECEIVER_CONTEXT_OR_EXTRA_MISMATCH");
            }
            Log.i("CS_FIXTURE", "FRAMEWORK_PROBE_CROSS_RECEIVER_FRAMEWORK_PASS package="
                    + context.getPackageName());
        }
        if (intent != null
                && "com.warden.controlledsandbox.fixture.FRAMEWORK_ORDERED_RECEIVER_PROBE"
                        .equals(intent.getAction())) {
            Bundle extras = new Bundle();
            extras.putString("orderedResult", "receiver");
            setResultCode(701);
            setResultData("ordered-framework");
            setResultExtras(extras);
            Log.i("CS_FIXTURE", "FRAMEWORK_PROBE_ORDERED_RECEIVER_DELIVERED");
        }
        if (intent != null
                && "com.warden.controlledsandbox.fixture.FRAMEWORK_ORDERED_RECEIVER_ASYNC_PROBE"
                        .equals(intent.getAction())) {
            final PendingResult pending = goAsync();
            Log.i("CS_FIXTURE", "FRAMEWORK_PROBE_ORDERED_ASYNC_RECEIVER_DELIVERED");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Bundle extras = new Bundle();
                extras.putString("orderedResult", "async-receiver");
                pending.setResultCode(702);
                pending.setResultData("ordered-async-framework");
                pending.setResultExtras(extras);
                try {
                    pending.finish();
                    Log.i("CS_FIXTURE", "FRAMEWORK_PROBE_ORDERED_ASYNC_RECEIVER_FINISHED");
                } catch (Throwable error) {
                    Log.e("CS_FIXTURE", "FRAMEWORK_PROBE_ORDERED_ASYNC_RECEIVER_FINISH_FAILED",
                            error);
                }
            }, 150L);
        }
    }
}
