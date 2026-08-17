package com.warden.controlledsandbox.fixture;

import android.app.Service;
import android.os.Build;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class FixtureService extends Service {
    private final FixtureBinder binder = new FixtureBinder();

    @Override public void onCreate() {
        super.onCreate();
        Log.i("CS_FIXTURE", "SERVICE_CREATE " + getClass().getName()
                + " process=" + getApplicationInfo().processName);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("CS_FIXTURE", "SERVICE_START id=" + startId
                + " action=" + (intent == null ? "" : intent.getAction()));
        // A sticky foreground Service is restarted with a null Intent.  It must still promote
        // itself again on the replacement ActivityThread Service instance; otherwise real AMS
        // and the virtual foreground registry diverge after process death.
        boolean isolatedProcess = getApplicationInfo().processName != null
                && getApplicationInfo().processName.contains(":isolated_");
        boolean recoveryIntent = !isolatedProcess
                && (intent == null || intent.getAction() == null);
        if (recoveryIntent || "com.warden.controlledsandbox.fixture.FOREGROUND_SERVICE"
                .equals(intent.getAction())) {
            int foregroundType = invokeForegroundTransport("fixture-foreground");
            Log.i("CS_FIXTURE", "FRAMEWORK_FGS_START_FOREGROUND_PASS type=" + foregroundType
                    + " recoveryIntent=" + recoveryIntent);
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
                Object notificationManager = getSystemService("notification");
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                int importance = Class.forName("android.app.NotificationManager")
                        .getField("IMPORTANCE_LOW").getInt(null);
                Object channel = channelClass.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(channelId, "Controlled Sandbox Fixture", importance);
                invokeNamed(notificationManager, "createNotificationChannel", channel);
            }
            java.lang.reflect.Method start = Service.class.getDeclaredMethod(
                    "startForeground", int.class, notificationClass);
            start.setAccessible(true);
            start.invoke(this, 42, notification);
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
    @Override public void onDestroy() {
        Log.i("CS_FIXTURE", "SERVICE_DESTROY " + getClass().getName());
        super.onDestroy();
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
