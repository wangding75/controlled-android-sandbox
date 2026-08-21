package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
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
 * process death and be held by the system process. Framework types are invoked reflectively so
 * host static compile stubs stay minimal.
 */
public final class SystemHolderPendingIntentActivity extends Activity {
    private static final String TAG = "CS_PI_HOLDER";
    private static final String CHANNEL_ID = "cas.system.holder";
    private static final int NOTIFICATION_ID = 5703;
    private static final long ALARM_DELAY_MS = 5_000L;
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
            try {
                postNotification(notificationSender);
            } catch (Exception notificationError) {
                Log.e(TAG, "NOTIFICATION_ARM_FAILED", notificationError);
            }

            Intent alarmIntent = new Intent(ACTION_ALARM)
                    .setPackage(getPackageName())
                    .putExtra("cas.pi.kind", "alarm");
            PendingIntent alarmSender = PendingIntent.getBroadcast(
                    this, 57032, alarmIntent, flags);
            scheduleAlarm(alarmSender);

            String payload = "{\"status\":\"ARMED\",\"notificationId\":" + NOTIFICATION_ID
                    + ",\"alarmAction\":\"" + ACTION_ALARM + "\""
                    + ",\"notificationAction\":\"" + ACTION_NOTIFICATION + "\""
                    + ",\"alarmDelayMs\":" + ALARM_DELAY_MS
                    + ",\"pid\":" + android.os.Process.myPid() + "}";
            File out = new File(getFilesDir(), "system-holder.json");
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            try {
                startService(new android.content.Intent(this, SystemHolderKeepAliveService.class));
            } catch (Exception keepAlive) {
                Log.w(TAG, "KEEP_ALIVE_FAILED", keepAlive);
            }
            Log.i(TAG, "ARMED notification=" + NOTIFICATION_ID + " alarm="
                    + ALARM_DELAY_MS + "ms pid="
                    + android.os.Process.myPid() + " file=" + out);
        } catch (Exception error) {
            Log.e(TAG, "ARM_FAILED", error);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // Stay alive so the harness can SIGKILL this :guestN after system holders are armed.
    }

    private void postNotification(PendingIntent content) throws Exception {
        Object manager = getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            Class<?> channelClass = Class.forName("android.app.NotificationChannel");
            int importance = Class.forName("android.app.NotificationManager")
                    .getField("IMPORTANCE_DEFAULT").getInt(null);
            Object channel = channelClass.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL_ID, "CAS system holder", importance);
            manager.getClass().getMethod("createNotificationChannel", channelClass)
                    .invoke(manager, channel);
        }
        Class<?> builderClass = Class.forName("android.app.Notification$Builder");
        Object builder = Build.VERSION.SDK_INT >= 26
                ? builderClass.getConstructor(android.content.Context.class, String.class)
                .newInstance(this, CHANNEL_ID)
                : builderClass.getConstructor(android.content.Context.class).newInstance(this);
        builderClass.getMethod("setSmallIcon", int.class).invoke(builder, 0x0108004a);
        builderClass.getMethod("setContentTitle", CharSequence.class).invoke(builder, "CAS system-holder");
        builderClass.getMethod("setContentText", CharSequence.class)
                .invoke(builder, "Notification holds guest PendingIntent");
        builderClass.getMethod("setContentIntent", PendingIntent.class).invoke(builder, content);
        builderClass.getMethod("setDeleteIntent", PendingIntent.class).invoke(builder, content);
        builderClass.getMethod("setAutoCancel", boolean.class).invoke(builder, false);
        Object posted = builderClass.getMethod("build").invoke(builder);
        manager.getClass().getMethod("notify", int.class, Class.forName("android.app.Notification"))
                .invoke(manager, NOTIFICATION_ID, posted);
    }

    private void scheduleAlarm(PendingIntent sender) throws Exception {
        Object manager = getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        int type = Class.forName("android.app.AlarmManager")
                .getField("ELAPSED_REALTIME_WAKEUP").getInt(null);
        long when = SystemClock.elapsedRealtime() + ALARM_DELAY_MS;
        try {
            manager.getClass().getMethod("setExact", int.class, long.class, PendingIntent.class)
                    .invoke(manager, type, when, sender);
            Log.i(TAG, "ALARM_SCHEDULED mode=setExact whenElapsed=" + when);
            return;
        } catch (Exception exactDenied) {
            Log.w(TAG, "setExact unavailable, falling back", exactDenied);
        }
        try {
            manager.getClass().getMethod("setExactAndAllowWhileIdle", int.class, long.class,
                    PendingIntent.class)
                    .invoke(manager, type, when, sender);
            Log.i(TAG, "ALARM_SCHEDULED mode=setExactAndAllowWhileIdle whenElapsed=" + when);
            return;
        } catch (Exception exactDenied) {
            Log.w(TAG, "setExactAndAllowWhileIdle unavailable, falling back", exactDenied);
        }
        try {
            manager.getClass().getMethod("setAndAllowWhileIdle", int.class, long.class, PendingIntent.class)
                    .invoke(manager, type, when, sender);
            Log.i(TAG, "ALARM_SCHEDULED mode=setAndAllowWhileIdle whenElapsed=" + when);
            return;
        } catch (Exception idleDenied) {
            Log.w(TAG, "setAndAllowWhileIdle unavailable, falling back", idleDenied);
        }
        manager.getClass().getMethod("set", int.class, long.class, PendingIntent.class)
                .invoke(manager, type, when, sender);
        Log.i(TAG, "ALARM_SCHEDULED mode=set whenElapsed=" + when);
    }
}
