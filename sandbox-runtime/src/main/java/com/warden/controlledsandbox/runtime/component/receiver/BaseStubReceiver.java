package com.warden.controlledsandbox.runtime.component.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fallback receiver declaration used only to allocate an Android RECEIVER transaction.
 * GuestActivityThreadServiceBridge consumes the transaction before this no-op callback runs.
 */
public abstract class BaseStubReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { }
}
