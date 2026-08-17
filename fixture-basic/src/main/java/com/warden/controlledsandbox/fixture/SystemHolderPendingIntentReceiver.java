package com.warden.controlledsandbox.fixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Records system-held PendingIntent deliveries after the creator process may have died. */
public final class SystemHolderPendingIntentReceiver extends BroadcastReceiver {
    private static final String TAG = "CS_PI_HOLDER";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        String kind = intent == null ? "" : intent.getStringExtra("cas.pi.kind");
        Log.i(TAG, "DELIVERED action=" + action + " kind=" + kind
                + " pid=" + android.os.Process.myPid());
        try {
            File out = new File(context.getFilesDir(), "system-holder-delivered.json");
            String payload = "{\"status\":\"DELIVERED\",\"action\":\"" + action
                    + "\",\"kind\":\"" + (kind == null ? "" : kind)
                    + "\",\"pid\":" + android.os.Process.myPid() + "}";
            try (FileOutputStream stream = new FileOutputStream(out, true)) {
                stream.write(payload.getBytes(StandardCharsets.UTF_8));
                stream.write('\n');
            }
        } catch (Exception error) {
            Log.e(TAG, "DELIVER_RECORD_FAILED", error);
        }
    }
}
