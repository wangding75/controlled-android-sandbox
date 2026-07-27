package com.warden.controlledsandbox.fixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class FixtureReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int count = context.getSharedPreferences("fixture", Context.MODE_PRIVATE).getInt("receiverCount", 0) + 1;
        context.getSharedPreferences("fixture", Context.MODE_PRIVATE).edit().putInt("receiverCount", count).commit();
        Log.i("CS_FIXTURE", "RECEIVER count=" + count + " action=" + (intent == null ? "" : intent.getAction()));
    }
}
