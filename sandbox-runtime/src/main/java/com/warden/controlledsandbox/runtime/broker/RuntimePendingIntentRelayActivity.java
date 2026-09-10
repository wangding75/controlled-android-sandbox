package com.warden.controlledsandbox.runtime.broker;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Activity-shaped host relay for an AMS-held Activity PendingIntent.
 *
 * <p>AMS dispatches a type-2 PendingIntent through ActivityStarter, so its relay component must
 * itself be an Activity.  The actual Guest Activity is selected by the Broker-owned token after
 * the relay crosses the process boundary; this trampoline finishes immediately and never exposes
 * a Guest component to the host resolver.</p>
 */
public final class RuntimePendingIntentRelayActivity extends Activity {
    public static final String CLASS_NAME = RuntimePendingIntentRelayActivity.class.getName();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        RuntimePendingIntentRelayReceiver.dispatchToBroker(this, getIntent());
        finish();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        RuntimePendingIntentRelayReceiver.dispatchToBroker(this, intent);
        finish();
    }
}
