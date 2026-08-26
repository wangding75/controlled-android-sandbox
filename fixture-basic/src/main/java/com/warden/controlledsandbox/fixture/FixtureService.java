package com.warden.controlledsandbox.fixture;

import android.app.Service;
import android.os.Build;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class FixtureService extends Service {
    private final FixtureBinder binder = new FixtureBinder();
    private String c2t05Session = "";
    private int c2t05Loop;

    @Override public void onCreate() {
        super.onCreate();
        Log.i("CS_FIXTURE", "SERVICE_CREATE " + getClass().getName()
                + " process=" + getApplicationInfo().processName);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("CS_FIXTURE", "SERVICE_START id=" + startId
                + " action=" + (intent == null ? "" : intent.getAction()));
        if (intent != null && "C1-T02_DEMOTE".equals(intent.getAction())) {
            invokeStopForeground();
            Log.i("CS_FIXTURE", "FRAMEWORK_FGS_STOP_FOREGROUND_PASS");
            return START_STICKY;
        }
        // A sticky foreground Service is restarted with a null Intent.  It must still promote
        // itself again on the replacement ActivityThread Service instance; otherwise real AMS
        // and the virtual foreground registry diverge after process death.
        boolean isolatedProcess = getApplicationInfo().processName != null
                && getApplicationInfo().processName.contains(":isolated_");
        boolean recoveryIntent = !isolatedProcess
                && (intent == null || intent.getAction() == null);
        if (recoveryIntent || "com.warden.controlledsandbox.fixture.FOREGROUND_SERVICE"
                .equals(intent.getAction()) || "com.warden.controlledsandbox.fixture.C2_T05_FGS"
                .equals(intent.getAction())) {
            if (intent != null && "com.warden.controlledsandbox.fixture.C2_T05_FGS"
                    .equals(intent.getAction())) {
                String requestedSession = intent.getStringExtra("c2t05Session");
                c2t05Session = requestedSession == null ? "" : requestedSession;
                c2t05Loop = intent.getIntExtra("c2t05Loop", 0);
            }
            int foregroundType = invokeForegroundTransport("fixture-foreground");
            Log.i("CS_FIXTURE", "FRAMEWORK_FGS_START_FOREGROUND_PASS type=" + foregroundType
                    + " recoveryIntent=" + recoveryIntent);
            if (!c2t05Session.isEmpty()) {
                String line = c2t05Session + " " + c2t05Loop + " type=" + foregroundType;
                Log.i("CS_C2_T05_FGS", "C2_T05_FGS_PROMOTED " + line);
                appendC2T05("c2-t05-fgs.log", line);
            }
        }
        return START_STICKY;
    }

    /** Uses reflection so the lightweight static Android stubs stay API-agnostic. */
    private int invokeForegroundTransport(String channelId) {
        try {
            Class<?> notificationClass = Class.forName("android.app.Notification");
            Class<?> builderClass = Class.forName("android.app.Notification$Builder");
            Object builder;
            try {
                builder = builderClass.getConstructor(android.content.Context.class, String.class)
                        .newInstance(this, channelId);
            } catch (NoSuchMethodException ignored) {
                builder = builderClass.getConstructor(android.content.Context.class)
                        .newInstance(this);
            }
            int icon = Class.forName("android.R$drawable").getField("ic_dialog_info").getInt(null);
            builderClass.getMethod("setSmallIcon", int.class).invoke(builder, icon);
            builderClass.getMethod("setContentTitle", CharSequence.class)
                    .invoke(builder, "Controlled Sandbox");
            builderClass.getMethod("setContentText", CharSequence.class)
                    .invoke(builder, "Framework foreground service probe");
            builderClass.getMethod("setOngoing", boolean.class).invoke(builder, true);
            Object notification = builderClass.getMethod("build").invoke(builder);

            if (Build.VERSION.SDK_INT >= 26) {
                Object notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                int importance = Class.forName("android.app.NotificationManager")
                        .getField("IMPORTANCE_LOW").getInt(null);
                Object channel = channelClass.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(channelId, "Controlled Sandbox Fixture", importance);
                invokeNamed(notificationManager, "createNotificationChannel", channel);
            }
            java.lang.reflect.Method start;
            boolean typedPromotion = Build.VERSION.SDK_INT >= 29;
            try {
                start = typedPromotion
                        ? Service.class.getDeclaredMethod("startForeground", int.class,
                                notificationClass, int.class)
                        : Service.class.getDeclaredMethod("startForeground", int.class,
                                notificationClass);
            } catch (NoSuchMethodException unsupportedTypedPromotion) {
                typedPromotion = false;
                start = Service.class.getDeclaredMethod("startForeground", int.class,
                        notificationClass);
            }
            start.setAccessible(true);
            if (typedPromotion) {
                // FOREGROUND_SERVICE_TYPE_DATA_SYNC is 0x1 on API29+; use the literal so the
                // API-agnostic static Android stubs need not model ServiceInfo constants.
                start.invoke(this, 42, notification, 0x1);
            } else {
                start.invoke(this, 42, notification);
            }
            java.lang.reflect.Method type = Service.class.getDeclaredMethod("getForegroundServiceType");
            type.setAccessible(true);
            return ((Number) type.invoke(this)).intValue();
        } catch (Throwable error) {
            throw new AssertionError("FRAMEWORK_FGS_REFLECTION_FAILED", error);
        }
    }

    private static void invokeNamed(Object target, String name, Object argument) throws Exception {
        if (target == null) throw new IllegalStateException("NOTIFICATION_MANAGER_NULL");
        for (java.lang.reflect.Method method : target.getClass().getMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().length == 1) {
                method.invoke(target, argument);
                return;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private void invokeStopForeground() {
        try {
            java.lang.reflect.Method stop = Service.class.getDeclaredMethod(
                    "stopForeground", boolean.class);
            stop.setAccessible(true);
            stop.invoke(this, true);
        } catch (Throwable error) {
            throw new AssertionError("FRAMEWORK_FGS_STOP_REFLECTION_FAILED", error);
        }
    }
    @Override public void onDestroy() {
        if (!c2t05Session.isEmpty()) {
            Log.i("CS_C2_T05_FGS", "C2_T05_FGS_STOPPED session=" + c2t05Session
                    + " loop=" + c2t05Loop);
            appendC2T05("c2-t05-fgs-stopped.log", c2t05Session + " " + c2t05Loop);
        }
        Log.i("CS_FIXTURE", "SERVICE_DESTROY " + getClass().getName());
        super.onDestroy();
    }

    private void appendC2T05(String name, String line) {
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), name), true)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.getFD().sync();
        } catch (Exception error) {
            Log.e("CS_C2_T05_FGS", "C2_T05_FGS_EVIDENCE_WRITE_FAILED", error);
        }
    }
    @Override public IBinder onBind(Intent intent) {
        Log.i("CS_FIXTURE", "SERVICE_BIND action=" + (intent == null ? "" : intent.getAction()));
        return binder;
    }
    @Override public boolean onUnbind(Intent intent) {
        Log.i("CS_FIXTURE", "SERVICE_UNBIND");
        return true;
    }
    @Override public void onRebind(Intent intent) {
        super.onRebind(intent);
        Log.i("CS_FIXTURE", "SERVICE_REBIND");
    }

    public static final class FixtureBinder extends Binder {
        public String ping() { return "BOUND_SERVICE_OK"; }
    }
}
