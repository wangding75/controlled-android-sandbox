package com.warden.controlledsandbox.runtime.broker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

/**
 * Host-process relay for Android-held PendingIntents. AMS can only send a real
 * PendingIntentRecord; that record targets this receiver in {@code :sandbox_server}
 * so delivery survives Guest stub death.
 */
public final class RuntimePendingIntentRelayReceiver extends BroadcastReceiver {
    public static final String ACTION =
            "com.warden.controlledsandbox.action.RELAY_PENDING_INTENT";
    public static final String CLASS_NAME = RuntimePendingIntentRelayReceiver.class.getName();

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String tokenId = intent.getStringExtra(RuntimeKeys.PENDING_INTENT_TOKEN_ID);
        android.util.Log.i("CS_PENDING_INTENT", "SYSTEM_HOLDER_RELAY token=" + tokenId);
        Intent service = new Intent(context, RuntimeBrokerService.class);
        service.setAction(ACTION);
        if (tokenId != null) service.putExtra(RuntimeKeys.PENDING_INTENT_TOKEN_ID, tokenId);
        context.startService(service);
    }
}
