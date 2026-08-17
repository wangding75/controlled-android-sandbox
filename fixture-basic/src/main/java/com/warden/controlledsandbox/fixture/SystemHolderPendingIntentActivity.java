package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Posts a Notification and a short Alarm whose PendingIntents are intended to survive guest
 * process death and be held by the system process.
 */
public final class SystemHolderPendingIntentActivity extends Activity {
    private static final String TAG = "CS_PI_HOLDER";
    private static final String CHANNEL_ID = "cas.system.holder";
    private static final int NOTIFICATION_ID = 5703;
    static final String ACTION_ALARM =
            "com.warden.controlledsandbox.fixture.SYSTEM_HOLDER_ALARM";
    static final String ACTION_NOTIFICATION =
            "com.warden.controlledsandbox.fixture.SYSTEM_HOLDER_NOTIFICATION";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_IMMUTABLE;
            else if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;

            Intent notificationIntent = new Intent(ACTION_NOTIFICATION)
                    .setPackage(getPackageName())
                    .putExtra("cas.pi.kind", "notification");
            PendingIntent notificationSender = PendingIntent.getBroadcast(
                    this, 57031, notificationIntent, flags);

            NotificationManager notifications =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && notifications != null) {
                notifications.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "CAS system holder", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            Notification posted = builder
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("CAS system-holder")
                    .setContentText("Notification holds guest PendingIntent")
                    .setContentIntent(notificationSender)
                    .setDeleteIntent(notificationSender)
                    .setAutoCancel(false)
                    .build();
            if (notifications != null) notifications.notify(NOTIFICATION_ID, posted);

            Intent alarmIntent = new Intent(ACTION_ALARM)
                    .setPackage(getPackageName())
                    .putExtra("cas.pi.kind", "alarm");
            PendingIntent alarmSender = PendingIntent.getBroadcast(
                    this, 57032, alarmIntent, flags);
            AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarms != null) {
                alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 8_000L, alarmSender);
            }

            String payload = "{\"status\":\"ARMED\",\"notificationId\":" + NOTIFICATION_ID
                    + ",\"alarmAction\":\"" + ACTION_ALARM + "\""
                    + ",\"notificationAction\":\"" + ACTION_NOTIFICATION + "\""
                    + ",\"pid\":" + android.os.Process.myPid() + "}";
            File out = new File(getFilesDir(), "system-holder.json");
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "ARMED notification=" + NOTIFICATION_ID + " alarm=8s file=" + out);
        } catch (Exception error) {
            Log.e(TAG, "ARM_FAILED", error);
        }
        finish();
    }
}
