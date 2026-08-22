package com.warden.controlledsandbox.fixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Package-neutral receiver used by the C2-T05 notification and exact-alarm callbacks. */
public final class C2T05EventReceiver extends BroadcastReceiver {
    static final String ACTION_NOTIFICATION_CLICK =
            "com.warden.controlledsandbox.fixture.C2_T05_NOTIFICATION_CLICK";
    static final String ACTION_NOTIFICATION_DELETE =
            "com.warden.controlledsandbox.fixture.C2_T05_NOTIFICATION_DELETE";
    static final String ACTION_EXACT_ALARM =
            "com.warden.controlledsandbox.fixture.C2_T05_EXACT_ALARM";
    private static final String TAG = "CS_C2_T05_EVENT";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        String session = intent == null ? "" : intent.getStringExtra("session");
        String kind = intent == null ? "" : intent.getStringExtra("kind");
        String marker = ACTION_NOTIFICATION_CLICK.equals(action)
                ? "C2_T05_NOTIFICATION_CLICK_CALLBACK"
                : ACTION_NOTIFICATION_DELETE.equals(action)
                ? "C2_T05_NOTIFICATION_DELETE_CALLBACK"
                : ACTION_EXACT_ALARM.equals(action)
                ? "C2_T05_ALARM_CALLBACK" : "C2_T05_EVENT_CALLBACK";
        String line = marker + " action=" + action + " kind=" + String.valueOf(kind)
                + " session=" + String.valueOf(session)
                + " pid=" + android.os.Process.myPid();
        Log.i(TAG, line);
        try (FileOutputStream output = new FileOutputStream(
                new File(context.getFilesDir(), "c2-t05-events.log"), true)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.getFD().sync();
        } catch (Exception error) {
            Log.e(TAG, "C2_T05_EVENT_WRITE_FAILED", error);
        }
    }
}
