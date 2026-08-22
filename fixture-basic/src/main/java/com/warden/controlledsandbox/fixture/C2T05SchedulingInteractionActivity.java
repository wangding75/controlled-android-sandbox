package com.warden.controlledsandbox.fixture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Package-neutral C2-T05 scheduling and interaction campaign fixture. */
@SuppressLint({"MissingPermission", "NewApi", "WrongConstant"})
public final class C2T05SchedulingInteractionActivity extends Activity {
    private static final String TAG = "CS_C2_T05";
    private static final int DEFAULT_LOOPS = 20;
    private static final int MAX_LOOPS = 100;
    // Keep the ordinary callback probe outside FixtureJobWorkItemScheduleActivity's reserved
    // 1805..2804 range; otherwise the Guest service would intentionally enter the work-item
    // branch and reject this probe's empty work queue.
    private static final int JOB_ID_BASE = 3400;
    private static final int ARM_NOTIFICATION_ID = 2605;
    private static final int ARM_REQUEST_CODE = 26051;
    private static final String ARM_TAG = "c2t05-arm";
    private static final String ARM_CHANNEL = "c2t05-arm-channel";
    private static final long CALLBACK_TIMEOUT_SECONDS = 12L;

    private final String session = UUID.randomUUID().toString();
    private final android.os.Handler main = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private volatile boolean destroyed;
    private View editor;
    private IBinder interactionWindowToken;
    private Object attachedWindowManager;
    private View attachedWindowRoot;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        editor = new View(this);
        setContentView(editor);
        Bundle extras = launchExtras();
        String mode = extras.getString("c2t05Mode", "full").trim().toLowerCase();
        int loops = Math.max(1, Math.min(MAX_LOOPS,
                extras.getInt("c2t05Loops", DEFAULT_LOOPS)));
        Log.i(TAG, "C2_T05_BEGIN mode=" + mode + " loops=" + loops
                + " session=" + session + " pid=" + android.os.Process.myPid());
        new Thread(() -> runCampaign(mode, loops), "c2-t05-campaign").start();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        try {
            if (attachedWindowManager != null && attachedWindowRoot != null) {
                invoke(attachedWindowManager, "removeViewImmediate",
                        new Class<?>[]{View.class}, attachedWindowRoot);
                attachedWindowManager = null;
                attachedWindowRoot = null;
            }
            Object input = getSystemService("input_method");
            IBinder inputToken = interactionWindowToken != null
                    ? interactionWindowToken : editor == null ? null : editor.getWindowToken();
            if (input != null && inputToken != null) {
                invoke(input, "hideSoftInputFromWindow",
                        new Class<?>[]{IBinder.class, int.class}, inputToken, 0);
            }
        } catch (Throwable error) {
            Log.w(TAG, "C2_T05_INPUT_CLEANUP_WARN " + error.getClass().getSimpleName());
        }
        Log.i(TAG, "C2_T05_INTERACTION_CLEANUP session=" + session
                + " pid=" + android.os.Process.myPid());
        super.onDestroy();
    }

    private Bundle launchExtras() {
        Bundle extras = getIntent() == null ? null : getIntent().getExtras();
        if (extras != null && extras.getBundle("intentExtras") != null) {
            extras = extras.getBundle("intentExtras");
        }
        return extras == null ? new Bundle() : new Bundle(extras);
    }

    private void runCampaign(String mode, int loops) {
        boolean leaveOpen = "arm".equals(mode);
        try {
            deleteFile("c2-t05-events.log");
            if ("cleanup".equals(mode)) {
                cleanupArmedState();
                Log.i(TAG, "C2_T05_CLEANUP_PASS session=" + session);
                return;
            }
            if (leaveOpen) {
                armSystemHeldPair();
                while (!destroyed) Thread.sleep(1_000L);
                return;
            }
            runInteractionProbe();
            for (int loop = 1; loop <= loops; loop++) runSchedulingLoop(loop);
            Log.i(TAG, "C2_T05_CAMPAIGN_PASS loops=" + loops + " session=" + session);
        } catch (Throwable error) {
            Log.e(TAG, "C2_T05_CAMPAIGN_FAIL mode=" + mode + " session=" + session
                    + " error=" + error.getClass().getSimpleName()
                    + " message=" + String.valueOf(error.getMessage()), error);
        } finally {
            if (!leaveOpen) main.post(this::finish);
        }
    }

    private void runInteractionProbe() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        Throwable[] failure = {null};
        // ActivityThread adds the Guest window after onCreate returns.  The campaign worker may
        // therefore reach this probe before the first resume transaction; wait for that frame so
        // the token check measures the real window lifecycle rather than a startup race.
        main.postDelayed(() -> {
            try {
                View decor = getWindow().getDecorView();
                IBinder windowToken = decor.getWindowToken();
                if (windowToken == null) {
                    // This campaign uses the package-neutral Activity bridge, whose Guest
                    // Activity owns a separate PhoneWindow from the physical Stub. Register the
                    // Guest decor through the virtual WindowManager so the returned View token
                    // is backed by the WindowSession ownership ledger before it is asserted.
                    attachGuestWindow(decor);
                    windowToken = decor.getWindowToken();
                }
                String tokenSource = "decor_window_token";
                if (windowToken == null) {
                    // The legacy package-neutral Activity bridge can keep the Guest PhoneWindow
                    // outside WindowManagerGlobal even after addView; its framework Activity
                    // token is the authoritative token used by the attached Stub route.
                    windowToken = activityFrameworkToken();
                    tokenSource = "activity_framework_token";
                }
                if (windowToken == null) throw new IllegalStateException("WINDOW_TOKEN_MISSING");
                interactionWindowToken = windowToken;

                Object windowManager = getSystemService("window");
                if (windowManager == null) throw new IllegalStateException("WINDOW_MANAGER_NULL");
                Object defaultDisplay = invoke(windowManager, "getDefaultDisplay", new Class<?>[0]);
                if (defaultDisplay == null) throw new IllegalStateException("DEFAULT_DISPLAY_NULL");
                Class<?> displayClass = Class.forName("android.view.Display");
                int defaultDisplayId = intValue(invoke(defaultDisplay, "getDisplayId", new Class<?>[0]), -1);

                Object displayManager = getSystemService("display");
                if (displayManager == null) throw new IllegalStateException("DISPLAY_MANAGER_NULL");
                Object displays = invoke(displayManager, "getDisplays", new Class<?>[0]);
                Object projected = invoke(displayManager, "getDisplay", new Class<?>[]{int.class},
                        defaultDisplayId);
                if (projected == null) throw new IllegalStateException("PROJECTED_DISPLAY_NULL");
                Class<?> metricsClass = Class.forName("android.util.DisplayMetrics");
                Object metrics = newInstance(metricsClass, new Class<?>[0]);
                invoke(projected, "getMetrics", new Class<?>[]{metricsClass}, metrics);
                int width = intField(metrics, "widthPixels", 0);
                int height = intField(metrics, "heightPixels", 0);
                int density = intField(metrics, "densityDpi", 0);
                if (width < 1 || height < 1) throw new IllegalStateException("DISPLAY_METRICS_EMPTY");
                boolean defaultFound = false;
                StringBuilder ids = new StringBuilder();
                if (displays != null && displays.getClass().isArray()) {
                    for (int index = 0; index < Array.getLength(displays); index++) {
                        Object display = Array.get(displays, index);
                        int id = intValue(invoke(display, "getDisplayId", new Class<?>[0]), -1);
                        if (ids.length() != 0) ids.append(',');
                        ids.append(id);
                        if (id == defaultDisplayId) defaultFound = true;
                    }
                }
                if (!defaultFound) throw new IllegalStateException("DEFAULT_DISPLAY_NOT_PROJECTED");
                Context displayContext = (Context) invoke(this, "createDisplayContext",
                        new Class<?>[]{displayClass}, projected);
                if (!getPackageName().equals(displayContext.getPackageName())) {
                    throw new IllegalStateException("DISPLAY_CONTEXT_PACKAGE_DRIFT"
                            + displayContext.getPackageName());
                }

                Object input = getSystemService("input_method");
                if (input == null) throw new IllegalStateException("INPUT_METHOD_MANAGER_NULL");
                invoke(editor, "requestFocus", new Class<?>[0]);
                Object methodsValue = invoke(input, "getEnabledInputMethodList", new Class<?>[0]);
                if (!(methodsValue instanceof List<?>)) throw new IllegalStateException("IME_LIST_NULL");
                List<?> methods = (List<?>) methodsValue;
                boolean shown = boolValue(invoke(input, "showSoftInput",
                        new Class<?>[]{View.class, int.class}, editor,
                        staticInt(Class.forName("android.view.inputmethod.InputMethodManager"),
                                "SHOW_IMPLICIT", 1)), false);
                boolean active = boolValue(invoke(input, "isActive",
                        new Class<?>[]{View.class}, editor), false);
                boolean hidden = boolValue(invoke(input, "hideSoftInputFromWindow",
                        new Class<?>[]{IBinder.class, int.class}, windowToken, 0), false);
                if (!methods.isEmpty()) throw new IllegalStateException("HOST_IME_CATALOG_LEAK");
                Log.i(TAG, "C2_T05_WINDOW_TOKEN_PASS tokenPresent=true displayId="
                        + defaultDisplayId + " source=" + tokenSource + " session=" + session);
                Log.i(TAG, "C2_T05_DISPLAY_CONTEXT_PASS ids=" + ids + " width=" + width
                        + " height=" + height + " density=" + density + " package="
                        + displayContext.getPackageName() + " session=" + session);
                Log.i(TAG, "C2_T05_IME_PASS enabledCount=" + methods.size()
                        + " showReturn=" + shown + " active=" + active
                        + " hideReturn=" + hidden + " session=" + session);
            } catch (Throwable error) {
                failure[0] = error;
            } finally {
                completed.countDown();
            }
        }, 750L);
        if (!completed.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("INTERACTION_PROBE_TIMEOUT");
        }
        if (failure[0] != null) throw new IllegalStateException("INTERACTION_PROBE_FAILED", failure[0]);
        Log.i(TAG, "C2_T05_INTERACTION_PASS session=" + session);
    }

    private void runSchedulingLoop(int loop) throws Exception {
        deleteFile("c2-t05-events.log");
        runNotification(loop);
        runExactAlarm(loop);
        runJob(loop);
        runForegroundService(loop);
        Log.i(TAG, "C2_T05_LOOP_PASS loop=" + loop + " session=" + session);
    }

    private void attachGuestWindow(View decor) throws Exception {
        Object windowManager = getWindowManager();
        if (windowManager == null) throw new IllegalStateException("WINDOW_MANAGER_NULL");
        android.view.WindowManager.LayoutParams layout = getWindow().getAttributes();
        if (layout.token == null) layout.token = activityFrameworkToken();
        layout.type = staticInt(Class.forName("android.view.WindowManager$LayoutParams"),
                "TYPE_BASE_APPLICATION", 1);
        invoke(windowManager, "addView",
                new Class<?>[]{View.class, Class.forName("android.view.ViewGroup$LayoutParams")},
                decor, layout);
        attachedWindowManager = windowManager;
        attachedWindowRoot = decor;
        Log.i(TAG, "C2_T05_WINDOW_ATTACH_PASS tokenSource=activity_framework_token session="
                + session);
    }

    private IBinder activityFrameworkToken() throws Exception {
        Field field = Activity.class.getDeclaredField("mToken");
        field.setAccessible(true);
        Object value = field.get(this);
        if (!(value instanceof IBinder)) throw new IllegalStateException("ACTIVITY_TOKEN_MISSING");
        return (IBinder) value;
    }

    private void runNotification(int loop) throws Exception {
        Object manager = getSystemService("notification");
        if (manager == null) throw new IllegalStateException("NOTIFICATION_MANAGER_NULL");
        String suffix = session.substring(0, 8) + "-" + loop;
        String channelId = "c2t05-channel-" + suffix;
        String tag = "c2t05-notification-" + suffix;
        int id = 2000 + loop;
        Class<?> managerClass = Class.forName("android.app.NotificationManager");
        Class<?> channelClass = Class.forName("android.app.NotificationChannel");
        Object channel = newInstance(channelClass,
                new Class<?>[]{String.class, CharSequence.class, int.class}, channelId,
                "C2-T05 " + loop, staticInt(managerClass, "IMPORTANCE_LOW", 2));
        invoke(manager, "createNotificationChannel", new Class<?>[]{channelClass}, channel);
        // Keep the PendingIntent identity stable across loops.  Android's normal
        // FLAG_UPDATE_CURRENT contract updates the sender payload instead of
        // issuing an unbounded sequence of equivalent remote records.
        PendingIntent click = PendingIntent.getBroadcast(this, 27000,
                eventIntent(C2T05EventReceiver.ACTION_NOTIFICATION_CLICK, "click"),
                pendingIntentFlags());
        PendingIntent delete = PendingIntent.getBroadcast(this, 28000,
                eventIntent(C2T05EventReceiver.ACTION_NOTIFICATION_DELETE, "delete"),
                pendingIntentFlags());
        Class<?> notificationClass = Class.forName("android.app.Notification");
        Class<?> builderClass = Class.forName("android.app.Notification$Builder");
        Object builder = newInstance(builderClass,
                new Class<?>[]{Context.class, String.class}, this, channelId);
        invoke(builder, "setSmallIcon", new Class<?>[]{int.class},
                staticInt(Class.forName("android.R$drawable"), "ic_dialog_info", 0x0108004a));
        invoke(builder, "setContentTitle", new Class<?>[]{CharSequence.class}, "C2-T05 notification");
        invoke(builder, "setContentText", new Class<?>[]{CharSequence.class},
                "Guest notification loop " + loop);
        invoke(builder, "setContentIntent", new Class<?>[]{PendingIntent.class}, click);
        invoke(builder, "setDeleteIntent", new Class<?>[]{PendingIntent.class}, delete);
        invoke(builder, "setAutoCancel", new Class<?>[]{boolean.class}, false);
        Object notification = invoke(builder, "build", new Class<?>[0]);
        // The actual framework request is NotificationManager.notify(tag, id, notification).
        invoke(manager, "notify", new Class<?>[]{String.class, int.class, notificationClass}, tag, id, notification);
        Object readback = invoke(manager, "getNotificationChannel",
                new Class<?>[]{String.class}, channelId);
        Object active = invoke(manager, "getActiveNotifications", new Class<?>[0]);
        int activeCount = active == null || !active.getClass().isArray() ? 0 : Array.getLength(active);
        if (readback == null || activeCount < 1) {
            throw new IllegalStateException("NOTIFICATION_READBACK_MISSING channel=" + channelId
                    + " active=" + activeCount);
        }
        Log.i(TAG, "C2_T05_NOTIFICATION_RETURN loop=" + loop + " tag=" + tag
                + " id=" + id + " channel=" + channelId + " active=" + activeCount
                + " session=" + session);
        click.send();
        awaitEvent("C2_T05_NOTIFICATION_CLICK_CALLBACK", session, CALLBACK_TIMEOUT_SECONDS);
        invoke(manager, "cancel", new Class<?>[]{String.class, int.class}, tag, id);
        delete.send();
        awaitEvent("C2_T05_NOTIFICATION_DELETE_CALLBACK", session, CALLBACK_TIMEOUT_SECONDS);
        invoke(manager, "deleteNotificationChannel", new Class<?>[]{String.class}, channelId);
        // NotificationManager no longer owns these senders after the notification and its
        // delete callback have completed.  Explicitly release the virtual sender records so a
        // long campaign cannot exhaust the Broker's bounded PendingIntent projection.
        invoke(click, "cancel", new Class<?>[0]);
        invoke(delete, "cancel", new Class<?>[0]);
        Log.i(TAG, "C2_T05_NOTIFICATION_PASS loop=" + loop + " session=" + session);
    }

    private void runExactAlarm(int loop) throws Exception {
        Object manager = getSystemService("alarm");
        if (manager == null) throw new IllegalStateException("ALARM_MANAGER_NULL");
        if (Build.VERSION.SDK_INT >= 31 && !boolValue(
                invoke(manager, "canScheduleExactAlarms", new Class<?>[0]), false)) {
            throw new SecurityException("EXACT_ALARM_PERMISSION_NOT_GRANTED");
        }
        // Reuse the alarm sender identity for the same reason as the
        // notification callbacks above; the trigger time is refreshed per loop.
        int requestCode = 29000;
        PendingIntent sender = PendingIntent.getBroadcast(this, requestCode,
                eventIntent(C2T05EventReceiver.ACTION_EXACT_ALARM, "exact-alarm"),
                pendingIntentFlags());
        long triggerAt = System.currentTimeMillis() + 450L;
        invoke(manager, "setExactAndAllowWhileIdle",
                new Class<?>[]{int.class, long.class, PendingIntent.class},
                staticInt(Class.forName("android.app.AlarmManager"), "RTC_WAKEUP", 0),
                triggerAt, sender);
        Log.i(TAG, "C2_T05_ALARM_RETURN loop=" + loop + " exact=true triggerAt="
                + triggerAt + " requestCode=" + requestCode + " session=" + session);
        awaitEvent("C2_T05_ALARM_CALLBACK", session, CALLBACK_TIMEOUT_SECONDS);
        invoke(manager, "cancel", new Class<?>[]{PendingIntent.class}, sender);
        invoke(sender, "cancel", new Class<?>[0]);
        Log.i(TAG, "C2_T05_ALARM_PASS loop=" + loop + " session=" + session);
    }

    private void runJob(int loop) throws Exception {
        // JobInfo.Builder and DisplayManager are loaded reflectively because the repository's
        // clean-room static Android stubs intentionally expose only their minimum surface.
        Object scheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) throw new IllegalStateException("JOB_SCHEDULER_NULL");
        int jobId = JOB_ID_BASE + loop;
        File callback = new File(getFilesDir(), "job-callback-" + jobId + ".json");
        if (callback.exists() && !callback.delete()) throw new IllegalStateException(
                "JOB_CALLBACK_FILE_DELETE_FAILED");
        PersistableBundle extras = new PersistableBundle();
        extras.putString("runId", "c2t05-" + session + "-" + loop);
        Class<?> jobInfoClass = Class.forName("android.app.job.JobInfo");
        Class<?> builderClass = Class.forName("android.app.job.JobInfo$Builder");
        Object builder = newInstance(builderClass,
                new Class<?>[]{int.class, ComponentName.class}, jobId,
                new ComponentName(this, FixtureJobService.class));
        invoke(builder, "setRequiredNetworkType", new Class<?>[]{int.class},
                staticInt(jobInfoClass, "NETWORK_TYPE_ANY", 1));
        invoke(builder, "setRequiresCharging", new Class<?>[]{boolean.class}, false);
        invoke(builder, "setRequiresBatteryNotLow", new Class<?>[]{boolean.class}, false);
        invoke(builder, "setRequiresStorageNotLow", new Class<?>[]{boolean.class}, false);
        invoke(builder, "setMinimumLatency", new Class<?>[]{long.class}, 1_000L);
        invoke(builder, "setOverrideDeadline", new Class<?>[]{long.class}, 8_000L);
        invoke(builder, "setBackoffCriteria", new Class<?>[]{long.class, int.class},
                1_000L, staticInt(jobInfoClass, "BACKOFF_POLICY_LINEAR", 0));
        invoke(builder, "setExtras", new Class<?>[]{PersistableBundle.class}, extras);
        Object job = invoke(builder, "build", new Class<?>[0]);
        int result = intValue(invoke(scheduler, "schedule",
                new Class<?>[]{jobInfoClass}, job), -1);
        Object pending = invoke(scheduler, "getPendingJob", new Class<?>[]{int.class}, jobId);
        int pendingId = pending == null ? -1 : intValue(invoke(pending, "getId", new Class<?>[0]), -1);
        Object service = pending == null ? null : invoke(pending, "getService", new Class<?>[0]);
        String serviceName = service == null ? "" : String.valueOf(
                invoke(service, "getClassName", new Class<?>[0]));
        // JobScheduler returns the trusted Host bridge component after the framework hook has
        // rewritten the Guest JobService. The Guest component is still the request source and
        // is proven by the callback file below; requiring FixtureJobService here would reject
        // the intentional package-neutral bridge projection as a false identity failure.
        if (result != 1 || pending == null || pendingId != jobId
                || !"com.warden.controlledsandbox.VirtualJobService".equals(serviceName)) {
            throw new IllegalStateException("JOB_RETURN_MISMATCH result=" + result
                    + " pending=" + pendingId + " service=" + serviceName);
        }
        int networkType = intValue(invoke(job, "getNetworkType", new Class<?>[0]), -1);
        Log.i(TAG, "C2_T05_JOB_RETURN loop=" + loop + " jobId=" + jobId
                + " networkType=" + networkType + " pendingId=" + pendingId
                + " session=" + session);
        if (!waitForFile(callback, 12_000L)) {
            invoke(scheduler, "cancel", new Class<?>[]{int.class}, jobId);
            throw new IllegalStateException("JOB_CALLBACK_TIMEOUT:" + jobId);
        }
        invoke(scheduler, "cancel", new Class<?>[]{int.class}, jobId);
        Log.i(TAG, "C2_T05_JOB_CALLBACK_PASS loop=" + loop + " jobId=" + jobId
                + " session=" + session);
    }

    private void runForegroundService(int loop) throws Exception {
        deleteFile("c2-t05-fgs.log");
        Intent request = new Intent(this, FixtureService.class)
                .setAction("com.warden.controlledsandbox.fixture.C2_T05_FGS")
                .putExtra("c2t05Session", session).putExtra("c2t05Loop", loop);
        ComponentName started = Build.VERSION.SDK_INT >= 26
                ? startForegroundService(request) : startService(request);
        Log.i(TAG, "C2_T05_FGS_RETURN loop=" + loop + " component="
                + String.valueOf(started) + " session=" + session);
        if (!waitForFileLine(new File(getFilesDir(), "c2-t05-fgs.log"), session + " " + loop,
                CALLBACK_TIMEOUT_SECONDS * 1000L)) {
            throw new IllegalStateException("FGS_PROMOTION_TIMEOUT:" + loop);
        }
        if (!stopService(new Intent(this, FixtureService.class))) {
            throw new IllegalStateException("FGS_STOP_RETURN_FALSE");
        }
        Log.i(TAG, "C2_T05_FGS_STOP_PASS loop=" + loop + " session=" + session);
    }

    private Intent eventIntent(String action, String kind) {
        return new Intent(this, C2T05EventReceiver.class).setAction(action)
                .putExtra("session", session).putExtra("kind", kind);
    }

    private void armSystemHeldPair() throws Exception {
        Object notification = getSystemService("notification");
        Object alarm = getSystemService("alarm");
        if (notification == null || alarm == null) throw new IllegalStateException("SYSTEM_MANAGER_NULL");
        Class<?> managerClass = Class.forName("android.app.NotificationManager");
        Class<?> channelClass = Class.forName("android.app.NotificationChannel");
        Object channel = newInstance(channelClass,
                new Class<?>[]{String.class, CharSequence.class, int.class}, ARM_CHANNEL,
                "C2-T05 armed", staticInt(managerClass, "IMPORTANCE_LOW", 2));
        invoke(notification, "createNotificationChannel", new Class<?>[]{channelClass}, channel);
        PendingIntent click = PendingIntent.getBroadcast(this, ARM_REQUEST_CODE + 1,
                eventIntent(C2T05EventReceiver.ACTION_NOTIFICATION_CLICK, "armed-click"),
                pendingIntentFlags());
        Class<?> notificationClass = Class.forName("android.app.Notification");
        Class<?> builderClass = Class.forName("android.app.Notification$Builder");
        Object builder = newInstance(builderClass,
                new Class<?>[]{Context.class, String.class}, this, ARM_CHANNEL);
        invoke(builder, "setSmallIcon", new Class<?>[]{int.class},
                staticInt(Class.forName("android.R$drawable"), "ic_dialog_info", 0x0108004a));
        invoke(builder, "setContentTitle", new Class<?>[]{CharSequence.class}, "C2-T05 armed");
        invoke(builder, "setContentText", new Class<?>[]{CharSequence.class}, "system-held death probe");
        invoke(builder, "setContentIntent", new Class<?>[]{PendingIntent.class}, click);
        invoke(builder, "setAutoCancel", new Class<?>[]{boolean.class}, false);
        Object value = invoke(builder, "build", new Class<?>[0]);
        invoke(notification, "notify", new Class<?>[]{String.class, int.class, notificationClass},
                ARM_TAG, ARM_NOTIFICATION_ID, value);
        PendingIntent alarmSender = PendingIntent.getBroadcast(this, ARM_REQUEST_CODE,
                eventIntent(C2T05EventReceiver.ACTION_EXACT_ALARM, "armed-alarm"),
                pendingIntentFlags());
        invoke(alarm, "setExactAndAllowWhileIdle",
                new Class<?>[]{int.class, long.class, PendingIntent.class},
                staticInt(Class.forName("android.app.AlarmManager"), "RTC_WAKEUP", 0),
                System.currentTimeMillis() + 5_000L, alarmSender);
        Log.i(TAG, "C2_T05_ARMED notificationTag=" + ARM_TAG + " notificationId="
                + ARM_NOTIFICATION_ID + " requestCode=" + ARM_REQUEST_CODE
                + " session=" + session + " pid=" + android.os.Process.myPid());
    }

    private void cleanupArmedState() throws Exception {
        Object notification = getSystemService("notification");
        Object alarm = getSystemService("alarm");
        if (notification != null) {
            invoke(notification, "cancel", new Class<?>[]{String.class, int.class},
                    ARM_TAG, ARM_NOTIFICATION_ID);
            if (Build.VERSION.SDK_INT >= 26) invoke(notification, "deleteNotificationChannel",
                    new Class<?>[]{String.class}, ARM_CHANNEL);
        }
        if (alarm != null) {
            PendingIntent click = PendingIntent.getBroadcast(this, ARM_REQUEST_CODE + 1,
                    eventIntent(C2T05EventReceiver.ACTION_NOTIFICATION_CLICK, "armed-click"),
                    pendingIntentFlags());
            invoke(click, "cancel", new Class<?>[0]);
            PendingIntent sender = PendingIntent.getBroadcast(this, ARM_REQUEST_CODE,
                    eventIntent(C2T05EventReceiver.ACTION_EXACT_ALARM, "armed-alarm"),
                    pendingIntentFlags());
            invoke(alarm, "cancel", new Class<?>[]{PendingIntent.class}, sender);
            invoke(sender, "cancel", new Class<?>[0]);
        }
    }

    private int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }

    private void awaitEvent(String marker, String expectedSession, long timeoutSeconds)
            throws Exception {
        if (!waitForFileLine(new File(getFilesDir(), "c2-t05-events.log"), marker,
                timeoutSeconds * 1000L)) {
            throw new IllegalStateException("EVENT_TIMEOUT:" + marker);
        }
        String content = readFile(new File(getFilesDir(), "c2-t05-events.log"));
        if (!content.contains(marker) || !content.contains("session=" + expectedSession)) {
            throw new IllegalStateException("EVENT_IDENTITY_MISMATCH:" + marker);
        }
    }

    private boolean waitForFile(File file, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile() && file.length() > 0L) return true;
            Thread.sleep(100L);
        }
        return file.isFile() && file.length() > 0L;
    }

    private boolean waitForFileLine(File file, String needle, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile() && readFile(file).contains(needle)) return true;
            Thread.sleep(100L);
        }
        return file.isFile() && readFile(file).contains(needle);
    }

    private String readFile(File file) throws Exception {
        if (!file.isFile()) return "";
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static Object newInstance(Class<?> type, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Constructor<?> constructor = type.getConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... args) throws Exception {
        Method method = target.getClass().getMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }

    private static int staticInt(Class<?> type, String name, int fallback) {
        try {
            Field field = type.getField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int intField(Object target, String name, int fallback) {
        if (target == null) return fallback;
        try {
            Field field = target.getClass().getField(name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static boolean boolValue(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }
}
