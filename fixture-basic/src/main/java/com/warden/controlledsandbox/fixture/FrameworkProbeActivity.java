package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Array;

/** Guest-side API probe for the RD API32 component and transport gates. */
public final class FrameworkProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private boolean serviceConnected;
    private boolean crossPackageServiceConnected;
    private ServiceConnection connection;
    private ServiceConnection crossPackageConnection;
    private Intent remoteServiceIntent;
    private Intent crossPackageServiceIntent;
    private DynamicFixtureReceiver dynamicReceiver;
    private ContentObserver crossProviderObserver;
    private volatile int crossProviderObserverChanges;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(TAG, "FRAMEWORK_PROBE_BEGIN package=" + getPackageName());
        dynamicReceiver = new DynamicFixtureReceiver();
        IntentFilter dynamicFilter = new IntentFilter(getPackageName() + ".DYNAMIC_PING");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dynamicReceiver, dynamicFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(dynamicReceiver, dynamicFilter);
        }
        providerBulkInsertProbe();
        providerBatchProbe();
        pendingIntentProbe();
        notificationReadbackProbe();
        jobReadbackProbe();
        alarmClockReadbackProbe();
        frameworkReceiverProbe();
        frameworkOrderedReceiverProbe();
        frameworkOrderedAsyncReceiverProbe();
        serviceBindingProbe();
        packageUniverseProbe();
        startActivity(new Intent(this, PersistableProbeActivity.class)
                .setAction(getPackageName() + ".PERSISTABLE_ACTIVITY_PROBE"));
        startActivity(new Intent(this, TaskSemanticsProbeActivity.class)
                .setAction(getPackageName() + ".TASK_SEMANTICS_PROBE"));
        crossPackageComponentProbe();
        multiProcessProbe();
        new Handler(Looper.getMainLooper()).postDelayed(this::finishProbe, 5000L);
    }

    private void providerBulkInsertProbe() {
        ContentResolver resolver = getContentResolver();
        Uri uri = Uri.parse("content://" + getPackageName() + ".provider");
        ContentValues first = new ContentValues();
        first.put("value", "bulk-one");
        ContentValues second = new ContentValues();
        second.put("value", "bulk-two");
        try {
            int inserted = resolver.bulkInsert(uri, new ContentValues[]{first, second});
            if (inserted != 2) throw new AssertionError("PROVIDER_BULK_RESULT_COUNT=" + inserted);
            Log.i(TAG, "FRAMEWORK_PROBE_PROVIDER_BULK_PASS count=" + inserted);
        } catch (Exception error) {
            throw new AssertionError("PROVIDER_BULK_FAILED:" + error, error);
        }
    }

    private void providerBatchProbe() {
        ContentResolver resolver = getContentResolver();
        Uri uri = Uri.parse("content://" + getPackageName() + ".provider");
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        operations.add(ContentProviderOperation.newInsert(uri)
                .withValue("value", "batch-insert")
                .build());
        operations.add(ContentProviderOperation.newUpdate(uri)
                .withValue("value", "batch-update")
                .build());
        Bundle callExtras = new Bundle();
        callExtras.putString("source", "framework-batch");
        operations.add(ContentProviderOperation.newCall(uri, "batch-call", "batch-arg")
                .withExtras(callExtras)
                .withYieldAllowed(true)
                .build());
        operations.add(ContentProviderOperation.newCall(uri, "batch-fail", null)
                .withExceptionAllowed(true)
                .build());
        // Android stores this as a hidden ContentProviderOperation.BackReference inside
        // mExtras. The Broker must project it explicitly instead of attempting to parcel the
        // framework object across the Guest boundary.
        ContentProviderOperation.Builder backReferenceCall =
                ContentProviderOperation.newCall(uri, "batch-call", "back-ref-arg");
        withExtraBackReference(backReferenceCall, "source", 2, "extra");
        operations.add(backReferenceCall.build());
        try {
            ContentProviderResult[] results = resolver.applyBatch(uri.getAuthority(), operations);
            if (results == null || results.length != operations.size()) {
                throw new AssertionError("PROVIDER_BATCH_RESULT_COUNT");
            }
            if (results[2] == null || results[2].extras == null
                    || !"batch-call".equals(results[2].extras.getString("method"))
                    || !"batch-arg".equals(results[2].extras.getString("arg"))
                    || !"framework-batch".equals(results[2].extras.getString("extra"))) {
                throw new AssertionError("PROVIDER_BATCH_CALL_RESULT");
            }
            if (results[3] == null || results[3].exception == null) {
                throw new AssertionError("PROVIDER_BATCH_EXCEPTION_RESULT");
            }
            if (results[4] == null || results[4].extras == null
                    || !"framework-batch".equals(results[4].extras.getString("extra"))) {
                throw new AssertionError("PROVIDER_BATCH_EXTRA_BACK_REFERENCE_RESULT");
            }
            try (Cursor cursor = resolver.query(uri, new String[]{"_id", "value"},
                    null, null, null)) {
                if (cursor == null || cursor.getCount() < 2) {
                    throw new AssertionError("PROVIDER_BATCH_QUERY_AFTER_APPLY");
                }
            }
            Log.i(TAG, "FRAMEWORK_PROBE_PROVIDER_BATCH_PASS count=" + results.length);
        } catch (Exception error) {
            throw new AssertionError("PROVIDER_BATCH_FAILED:" + error, error);
        }
    }

    private static void withExtraBackReference(ContentProviderOperation.Builder builder,
                                                String key, int fromIndex, String fromKey) {
        try {
            java.lang.reflect.Method method = builder.getClass().getMethod(
                    "withExtraBackReference", String.class, int.class, String.class);
            method.invoke(builder, key, fromIndex, fromKey);
        } catch (Throwable error) {
            throw new IllegalStateException("FRAMEWORK_EXTRA_BACK_REFERENCE_API_MISSING", error);
        }
    }

    private void pendingIntentProbe() {
        Intent target = new Intent(this, DetailActivity.class)
                .setAction(getPackageName() + ".PENDING_INTENT_TARGET");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        else if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        // Keep the request code fresh so a prior immutable probe record cannot be reused by
        // Android's PendingIntent identity matching across fixture generations.
        PendingIntent pending = PendingIntent.getActivity(this, 157, target, flags);
        if (pending == null) throw new AssertionError("PENDING_INTENT_NULL");
        try {
            // Use an explicit mutable sender so this probe isolates IIntentSender transport and
            // delivery rather than the separate immutable fill-in policy gate.
            pending.send();
            Log.i(TAG, "FRAMEWORK_PROBE_PENDING_INTENT_PASS");
        } catch (PendingIntent.CanceledException error) {
            throw new AssertionError("PENDING_INTENT_SEND_FAILED", error);
        }
        // PendingIntent.send() is also intercepted at the ActivityManager call surface.  Keep a
        // second, raw IIntentSender transact so the fixture proves the Binder retained by a
        // system process can reach the Broker-owned relay directly.
        new Thread(() -> rawPendingIntentBinderProbe(pending), "cs-pending-intent-binder-probe").start();
    }

    private void notificationReadbackProbe() {
        try {
            Object manager = getSystemService("notification");
            if (manager == null) throw new AssertionError("NOTIFICATION_MANAGER_NULL");
            String channelId = "framework-readback";
            if (Build.VERSION.SDK_INT >= 26) {
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                int importance = Class.forName("android.app.NotificationManager")
                        .getField("IMPORTANCE_LOW").getInt(null);
                Object channel = channelClass.getConstructor(String.class, CharSequence.class,
                        int.class).newInstance(channelId, "Framework readback", importance);
                invokeObject(manager, "createNotificationChannel",
                        new Class<?>[]{channelClass}, channel);
            }
            Class<?> notificationClass = Class.forName("android.app.Notification");
            Class<?> builderClass = Class.forName("android.app.Notification$Builder");
            Object builder = Build.VERSION.SDK_INT >= 26
                    ? builderClass.getConstructor(Context.class, String.class)
                            .newInstance(this, channelId)
                    : builderClass.getConstructor(Context.class).newInstance(this);
            int icon = Class.forName("android.R$drawable").getField("ic_dialog_info").getInt(null);
            invokeObject(builder, "setSmallIcon", new Class<?>[]{int.class}, icon);
            invokeObject(builder, "setContentTitle", new Class<?>[]{CharSequence.class},
                    "framework-readback");
            invokeObject(builder, "setContentText", new Class<?>[]{CharSequence.class},
                    "virtual notification");
            Object notification = invokeObject(builder, "build", new Class<?>[0]);
            invokeObject(manager, "notify",
                    new Class<?>[]{String.class, int.class, notificationClass},
                    "framework-readback", 371, notification);
            boolean found = false;
            Object active = invokeObject(manager, "getActiveNotifications", new Class<?>[0]);
            int activeCount = active == null || !active.getClass().isArray()
                    ? 0 : Array.getLength(active);
            for (int index = 0; index < activeCount; index++) {
                Object value = Array.get(active, index);
                if (value != null
                        && ((Number) invokeObject(value, "getId", new Class<?>[0])).intValue() == 371
                        && getPackageName().equals(invokeObject(value, "getPackageName", new Class<?>[0]))
                        && "framework-readback".equals(invokeObject(value, "getTag", new Class<?>[0]))) {
                    found = true;
                    break;
                }
            }
            invokeObject(manager, "cancel", new Class<?>[]{String.class, int.class},
                    "framework-readback", 371);
            if (!found) throw new AssertionError("NOTIFICATION_READBACK_MISSING");
            Log.i(TAG, "FRAMEWORK_PROBE_NOTIFICATION_READBACK_PASS");
        } catch (Throwable error) {
            throw new AssertionError("NOTIFICATION_READBACK_FAILED:" + error, error);
        }
    }

    private void jobReadbackProbe() {
        try {
            Object scheduler = getSystemService("jobscheduler");
            if (scheduler == null) throw new AssertionError("JOB_SCHEDULER_NULL");
            Class<?> builderClass = Class.forName("android.app.job.JobInfo$Builder");
            Class<?> jobInfoClass = Class.forName("android.app.job.JobInfo");
            Object builder = builderClass.getConstructor(int.class, ComponentName.class)
                    .newInstance(371, new ComponentName(this, FixtureJobService.class));
            invokeObject(builder, "setMinimumLatency", new Class<?>[]{long.class},
                    10 * 60 * 1000L);
            Object job = invokeObject(builder, "build", new Class<?>[0]);
            int result = ((Number) invokeObject(scheduler, "schedule",
                    new Class<?>[]{jobInfoClass}, job)).intValue();
            if (result != 1) {
                throw new AssertionError("JOB_SCHEDULE_RESULT=" + result);
            }
            Object projected = invokeObject(scheduler, "getPendingJob",
                    new Class<?>[]{int.class}, 371);
            invokeObject(scheduler, "cancel", new Class<?>[]{int.class}, 371);
            if (projected == null
                    || ((Number) invokeObject(projected, "getId", new Class<?>[0])).intValue() != 371) {
                throw new AssertionError("JOB_READBACK_MISMATCH");
            }
            Log.i(TAG, "FRAMEWORK_PROBE_JOB_READBACK_PASS id="
                    + invokeObject(projected, "getId", new Class<?>[0]));
        } catch (Throwable error) {
            throw new AssertionError("JOB_READBACK_FAILED:" + error, error);
        }
    }

    private void alarmClockReadbackProbe() {
        try {
            Object manager = getSystemService("alarm");
            if (manager == null) throw new AssertionError("ALARM_MANAGER_NULL");
            Class<?> infoClass = Class.forName("android.app.AlarmManager$AlarmClockInfo");
            long trigger = System.currentTimeMillis() + 10 * 60 * 1000L;
            Intent target = new Intent(this, DetailActivity.class)
                    .setAction(getPackageName() + ".ALARM_CLOCK_PROBE");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            else if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent showIntent = PendingIntent.getActivity(this, 871, target, flags);
            Object info = infoClass.getConstructor(long.class, PendingIntent.class)
                    .newInstance(trigger, showIntent);
            invokeObject(manager, "setAlarmClock",
                    new Class<?>[]{infoClass, PendingIntent.class}, info, showIntent);
            Object projected = invokeObject(manager, "getNextAlarmClock", new Class<?>[0]);
            if (projected == null) throw new AssertionError("ALARM_CLOCK_READBACK_NULL");
            long projectedTrigger = ((Number) invokeObject(projected, "getTriggerTime",
                    new Class<?>[0])).longValue();
            Object projectedShow = invokeObject(projected, "getShowIntent", new Class<?>[0]);
            invokeObject(manager, "cancel", new Class<?>[]{PendingIntent.class}, showIntent);
            if (projectedShow == null || Math.abs(projectedTrigger - trigger) > 1000L) {
                throw new AssertionError("ALARM_CLOCK_READBACK_MISMATCH trigger=" + projectedTrigger);
            }
            Log.i(TAG, "FRAMEWORK_PROBE_ALARM_CLOCK_READBACK_PASS trigger=" + projectedTrigger);
        } catch (Throwable error) {
            throw new AssertionError("ALARM_CLOCK_READBACK_FAILED:" + error, error);
        }
    }

    private void rawPendingIntentBinderProbe(PendingIntent pending) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        java.util.concurrent.atomic.AtomicBoolean finishedCallback =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Intent> callbackIntent =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> callbackFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            Field targetField = PendingIntent.class.getDeclaredField("mTarget");
            targetField.setAccessible(true);
            Object target = targetField.get(pending);
            IBinder binder = target instanceof IBinder ? (IBinder) target :
                    target instanceof android.os.IInterface
                            ? ((android.os.IInterface) target).asBinder() : null;
            if (binder == null) throw new IllegalStateException("PENDING_INTENT_TARGET_BINDER_MISSING");
            invokeParcel(data, "writeInterfaceToken", new Class<?>[]{String.class},
                    "android.content.IIntentSender");
            data.writeInt(0); // result code
            data.writeInt(0); // no fill-in Intent
            data.writeString(null); // resolved type
            invokeParcel(data, "writeStrongBinder", new Class<?>[]{IBinder.class}, (IBinder) null);
            Binder receiver = new Binder() {
                @Override protected boolean onTransact(int code, Parcel callbackData,
                                                        Parcel callbackReply, int flags) {
                    if (code == 1) {
                        try {
                            callbackData.enforceInterface("android.content.IIntentReceiver");
                            boolean hasIntent = callbackData.readInt() != 0;
                            callbackIntent.set(hasIntent
                                    ? decodeIntentFromParcel(callbackData) : null);
                            callbackData.readInt(); // result code
                            finishedCallback.set(true);
                        } catch (Throwable error) {
                            callbackFailure.set(error);
                            finishedCallback.set(true);
                        }
                        return true;
                    }
                    return false;
                }
            };
            invokeParcel(data, "writeStrongBinder", new Class<?>[]{IBinder.class}, receiver);
            data.writeString(null); // required permission
            data.writeBundle(null); // options
            if (!binder.transact(1, data, reply, 0)) {
                throw new IllegalStateException("PENDING_INTENT_RAW_TRANSACT_REJECTED");
            }
            invokeParcel(reply, "readException", new Class<?>[0]);
            int result = reply.readInt();
            if (result < 0) throw new IllegalStateException("PENDING_INTENT_RAW_RESULT=" + result);
            Log.i(TAG, "FRAMEWORK_PROBE_PENDING_INTENT_BINDER_PASS result=" + result);
            long deadline = System.currentTimeMillis() + 2000L;
            while (!finishedCallback.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            if (!finishedCallback.get()) {
                throw new IllegalStateException("PENDING_INTENT_FINISHED_RECEIVER_MISSING");
            }
            if (callbackFailure.get() != null) {
                throw new IllegalStateException("PENDING_INTENT_CALLBACK_DECODE_FAILED",
                        callbackFailure.get());
            }
            Intent delivered = callbackIntent.get();
            if (delivered == null
                    || !((getPackageName() + ".PENDING_INTENT_TARGET").equals(
                            delivered.getAction()))) {
                throw new IllegalStateException("PENDING_INTENT_CALLBACK_INTENT_MISMATCH action="
                        + (delivered == null ? "<null>" : delivered.getAction()));
            }
            Log.i(TAG, "FRAMEWORK_PROBE_PENDING_INTENT_CALLBACK_PASS");
        } catch (Throwable error) {
            Log.e(TAG, "FRAMEWORK_PROBE_PENDING_INTENT_BINDER_FAIL", error);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Keep the API-32 static source harness small while using the public Parcel ABI on device. */
    private static Object invokeParcel(Parcel parcel, String method, Class<?>[] types,
                                       Object... arguments) throws Exception {
        java.lang.reflect.Method target = Parcel.class.getMethod(method, types);
        target.setAccessible(true);
        return target.invoke(parcel, arguments);
    }

    /** Uses the real framework Parcelable creator without requiring it in compact API stubs. */
    private static Intent decodeIntentFromParcel(Parcel parcel) throws Exception {
        Field creatorField = Intent.class.getField("CREATOR");
        creatorField.setAccessible(true);
        Object creator = creatorField.get(null);
        if (!(creator instanceof android.os.Parcelable.Creator<?>)) {
            throw new IllegalStateException("INTENT_CREATOR_UNAVAILABLE");
        }
        @SuppressWarnings("unchecked") android.os.Parcelable.Creator<Intent> typed =
                (android.os.Parcelable.Creator<Intent>) creator;
        return typed.createFromParcel(parcel);
    }

    private static Object invokeObject(Object receiver, String method, Class<?>[] types,
                                       Object... arguments) throws Exception {
        java.lang.reflect.Method target = receiver.getClass().getMethod(method, types);
        target.setAccessible(true);
        return target.invoke(receiver, arguments);
    }

    private void frameworkReceiverProbe() {
        Intent target = new Intent(this, FixtureReceiver.class)
                .setAction(getPackageName() + ".FRAMEWORK_RECEIVER_PROBE")
                .putExtra("frameworkReceiverValue", "activity-thread-receiver");
        sendBroadcast(target);
        Log.i(TAG, "FRAMEWORK_PROBE_RECEIVER_FRAMEWORK_REQUESTED component="
                + target.getComponent().flattenToShortString());
    }

    private void frameworkOrderedReceiverProbe() {
        Intent target = new Intent(this, FixtureReceiver.class)
                .setAction(getPackageName() + ".FRAMEWORK_ORDERED_RECEIVER_PROBE");
        Bundle initialExtras = new Bundle();
        initialExtras.putString("orderedInitial", "initial");
        sendOrderedBroadcast(target, null, new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int resultCode = getResultCode();
                String resultData = getResultData();
                Bundle resultExtras = getResultExtras(false);
                if (resultCode != 701 || !"ordered-framework".equals(resultData)
                        || resultExtras == null
                        || !"receiver".equals(resultExtras.getString("orderedResult"))) {
                    throw new AssertionError("FRAMEWORK_ORDERED_RECEIVER_RESULT_MISMATCH");
                }
                Log.i(TAG, "FRAMEWORK_PROBE_ORDERED_RECEIVER_FRAMEWORK_PASS code="
                        + resultCode + " data=" + resultData);
            }
        }, new Handler(Looper.getMainLooper()), 701, "initial-data", initialExtras);
    }

    private void frameworkOrderedAsyncReceiverProbe() {
        Intent target = new Intent(this, FixtureReceiver.class)
                .setAction(getPackageName() + ".FRAMEWORK_ORDERED_RECEIVER_ASYNC_PROBE");
        sendOrderedBroadcast(target, null, new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int resultCode = getResultCode();
                String resultData = getResultData();
                Bundle resultExtras = getResultExtras(false);
                if (resultCode != 702 || !"ordered-async-framework".equals(resultData)
                        || resultExtras == null
                        || !"async-receiver".equals(resultExtras.getString("orderedResult"))) {
                    throw new AssertionError("FRAMEWORK_ORDERED_ASYNC_RECEIVER_RESULT_MISMATCH");
                }
                Log.i(TAG, "FRAMEWORK_PROBE_ORDERED_ASYNC_RECEIVER_FRAMEWORK_PASS code="
                        + resultCode + " data=" + resultData);
            }
        }, new Handler(Looper.getMainLooper()), 702, "initial-async-data", new Bundle());
    }

    private void serviceBindingProbe() {
        Intent service = new Intent(this, FixtureService.class)
                .setAction(getPackageName() + ".SERVICE_BIND_PROBE");
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (binder == null) throw new AssertionError("SERVICE_BIND_NULL");
                serviceConnected = true;
                Log.i(TAG, "FRAMEWORK_PROBE_SERVICE_BIND_PASS component=" + name.flattenToShortString());
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, "FRAMEWORK_PROBE_SERVICE_DISCONNECTED component="
                        + name.flattenToShortString());
            }
        };
        if (!bindService(service, connection, BIND_AUTO_CREATE)) {
            throw new AssertionError("SERVICE_BIND_RETURNED_FALSE");
        }
    }

    private void multiProcessProbe() {
        Intent remoteActivity = new Intent(this, RemoteActivity.class)
                .setAction(getPackageName() + ".REMOTE_ACTIVITY_PROBE");
        startActivity(remoteActivity);
        remoteServiceIntent = new Intent(this, RemoteFixtureService.class)
                .setAction(getPackageName() + ".REMOTE_SERVICE_PROBE");
        ComponentName remoteService = startService(remoteServiceIntent);
        if (remoteService == null) throw new AssertionError("REMOTE_SERVICE_START_NULL");
        Log.i(TAG, "FRAMEWORK_PROBE_REMOTE_ROUTE_REQUESTED component="
                + remoteService.flattenToShortString());
    }

    private void packageUniverseProbe() {
        String peerPackage = "com.warden.controlledsandbox.fixture32";
        try {
            ApplicationInfo peer = getPackageManager().getApplicationInfo(peerPackage, 0);
            PackageInfo packageInfo = getPackageManager().getPackageInfo(peerPackage, 0);
            android.content.pm.ProviderInfo peerProvider = getPackageManager().resolveContentProvider(
                    peerPackage + ".provider", 0);
            android.content.pm.ActivityInfo peerActivity = getPackageManager().getActivityInfo(
                    new ComponentName(peerPackage,
                            "com.warden.controlledsandbox.fixture.MainActivity"), 0x00000080);
            android.content.pm.ServiceInfo peerService = getPackageManager().getServiceInfo(
                    new ComponentName(peerPackage,
                            "com.warden.controlledsandbox.fixture.FixtureService"), 0x00000080);
            android.content.pm.ActivityInfo peerReceiver = getPackageManager().getReceiverInfo(
                    new ComponentName(peerPackage,
                            "com.warden.controlledsandbox.fixture.FixtureReceiver"), 0x00000080);
            List<ResolveInfo> launchers = getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            boolean launcherVisible = false;
            for (ResolveInfo value : launchers) {
                if (value.activityInfo != null && peerPackage.equals(value.activityInfo.packageName)) {
                    launcherVisible = true;
                    break;
                }
            }
            Log.i(TAG, "FRAMEWORK_PROBE_COMPONENT_METADATA_VALUES activity="
                    + componentMetadata(peerActivity, "cas.fixture.activity_meta")
                    + " service=" + componentMetadata(peerService, "cas.fixture.service_meta")
                    + " receiver=" + componentMetadata(peerReceiver, "cas.fixture.receiver_meta")
                    + " provider=" + componentMetadata(peerProvider, "cas.fixture.provider_meta"));
            if (!peerPackage.equals(peer.packageName) || !peerPackage.equals(packageInfo.packageName)
                    || peerProvider == null || !peerPackage.equals(peerProvider.packageName)
                    || peer.uid <= 0 || peerProvider.applicationInfo == null
                    || peerProvider.applicationInfo.uid != peer.uid
                    || peerProvider.processName == null
                    || !peerProvider.processName.equals(peerPackage + ":provider")
                    || !"activity-peer".equals(componentMetadata(peerActivity, "cas.fixture.activity_meta"))
                    || !"service-peer".equals(componentMetadata(peerService, "cas.fixture.service_meta"))
                    || !"receiver-peer".equals(componentMetadata(peerReceiver, "cas.fixture.receiver_meta"))
                    || !"provider-peer".equals(componentMetadata(peerProvider, "cas.fixture.provider_meta"))
                    || !launcherVisible) {
                throw new AssertionError("VIRTUAL_PACKAGE_UNIVERSE_MISMATCH");
            }
            Log.i(TAG, "FRAMEWORK_PROBE_PACKAGE_UNIVERSE_PASS package=" + peerPackage
                    + " launchers=" + launchers.size());
            Log.i(TAG, "FRAMEWORK_PROBE_PACKAGE_IDENTITY_PASS uid=" + peer.uid
                    + " providerUid=" + peerProvider.applicationInfo.uid
                    + " providerProcess=" + peerProvider.processName);
            Log.i(TAG, "FRAMEWORK_PROBE_COMPONENT_METADATA_PASS activity="
                    + componentMetadata(peerActivity, "cas.fixture.activity_meta")
                    + " service=" + componentMetadata(peerService, "cas.fixture.service_meta")
                    + " receiver=" + componentMetadata(peerReceiver, "cas.fixture.receiver_meta")
                    + " provider=" + componentMetadata(peerProvider, "cas.fixture.provider_meta"));
            packageContextProbe(peerPackage, peer.uid);
            Uri peerProviderUri = Uri.parse("content://" + peerPackage + ".provider");
            try (Cursor cursor = getContentResolver().query(peerProviderUri,
                    new String[]{"_id", "value"}, null, null, null)) {
                if (cursor == null || cursor.getCount() < 1) {
                    throw new AssertionError("CROSS_PROVIDER_QUERY_EMPTY");
                }
                Log.i(TAG, "FRAMEWORK_PROBE_CROSS_PROVIDER_PASS authority="
                        + peerProviderUri.getAuthority() + " rows=" + cursor.getCount());
            }
            ContentResolver resolver = getContentResolver();
            crossProviderObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange, Uri changedUri) {
                    crossProviderObserverChanges++;
                    Log.i(TAG, "FRAMEWORK_PROBE_CROSS_PROVIDER_OBSERVER_DELIVERED uri="
                            + changedUri + " self=" + selfChange);
                }
            };
            resolver.registerContentObserver(peerProviderUri, true, crossProviderObserver);
            resolver.notifyChange(peerProviderUri, null);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (crossProviderObserverChanges < 1) {
                    throw new AssertionError("CROSS_PROVIDER_OBSERVER_NOT_DELIVERED");
                }
                Log.i(TAG, "FRAMEWORK_PROBE_CROSS_PROVIDER_OBSERVER_PASS count="
                        + crossProviderObserverChanges);
                try { resolver.unregisterContentObserver(crossProviderObserver); } catch (Throwable ignored) { }
            }, 500L);
        } catch (Exception error) {
            throw new AssertionError("VIRTUAL_PACKAGE_UNIVERSE_FAILED", error);
        }
    }

    private static String componentMetadata(Object componentInfo, String key) {
        if (componentInfo == null || key == null) return "";
        try {
            Class<?> type = componentInfo.getClass();
            Field field = null;
            while (type != null && field == null) {
                try { field = type.getDeclaredField("metaData"); }
                catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
            }
            if (field == null) return "";
            field.setAccessible(true);
            Object value = field.get(componentInfo);
            if (!(value instanceof Bundle)) return "";
            String result = ((Bundle) value).getString(key);
            return result == null ? "" : result;
        } catch (Throwable error) {
            return "";
        }
    }

    private void packageContextProbe(String peerPackage, int expectedUid) {
        try {
            Context resourcesOnly = createPackageContext(peerPackage, 0);
            // API32's Context constant is 0x1; use the value so this shared fixture also builds
            // against the reduced static API stubs used by the host-side contract gate.
            Context withCode = createPackageContext(peerPackage, 0x00000001);
            ApplicationInfo projected = withCode.getApplicationInfo();
            if (!peerPackage.equals(resourcesOnly.getPackageName())
                    || !peerPackage.equals(withCode.getPackageName())
                    || !getPackageName().equals(withCode.getOpPackageName())
                    || resourcesOnly.getResources() == null
                    || withCode.getResources() == null
                    || withCode.getClassLoader() == null
                    || projected == null
                    || !peerPackage.equals(projected.packageName)
                    || projected.uid != expectedUid
                    || projected.sourceDir == null
                    || !projected.sourceDir.startsWith("/data/app/")
                    || projected.sourceDir.contains("controlled-android-sandbox")) {
                throw new AssertionError("PACKAGE_CONTEXT_PROJECTION_MISMATCH");
            }
            Class<?> peerMain = withCode.getClassLoader().loadClass(
                    "com.warden.controlledsandbox.fixture.MainActivity");
            if (peerMain == null || !"com.warden.controlledsandbox.fixture.MainActivity"
                    .equals(peerMain.getName())) {
                throw new AssertionError("PACKAGE_CONTEXT_CODE_LOAD_MISMATCH");
            }
            Log.i(TAG, "FRAMEWORK_PROBE_PACKAGE_CONTEXT_PASS package=" + peerPackage
                    + " uid=" + projected.uid + " classLoader="
                    + withCode.getClassLoader().getClass().getName());
        } catch (Throwable error) {
            throw new AssertionError("PACKAGE_CONTEXT_FAILED:" + error, error);
        }
    }

    private void crossPackageComponentProbe() {
        String peerPackage = "com.warden.controlledsandbox.fixture32";
        String peerActivity = "com.warden.controlledsandbox.fixture.MainActivity";
        try {
            Intent activity = new Intent().setComponent(new ComponentName(peerPackage, peerActivity))
                    .setAction(getPackageName() + ".CROSS_PACKAGE_ACTIVITY_PROBE");
            startActivity(activity);
            Log.i(TAG, "FRAMEWORK_PROBE_CROSS_ACTIVITY_PASS component="
                    + activity.getComponent().flattenToShortString());

            crossPackageServiceIntent = new Intent()
                    .setComponent(new ComponentName(peerPackage,
                            "com.warden.controlledsandbox.fixture.FixtureService"))
                    .setAction(getPackageName() + ".CROSS_PACKAGE_SERVICE_PROBE");
            if (startService(crossPackageServiceIntent) == null) {
                throw new AssertionError("CROSS_SERVICE_START_RETURNED_NULL");
            }
            crossPackageConnection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    if (binder == null) throw new AssertionError("CROSS_SERVICE_BIND_NULL");
                    crossPackageServiceConnected = true;
                    Log.i(TAG, "FRAMEWORK_PROBE_CROSS_SERVICE_BIND_PASS component="
                            + name.flattenToShortString());
                }

                @Override public void onServiceDisconnected(ComponentName name) {
                    Log.i(TAG, "FRAMEWORK_PROBE_CROSS_SERVICE_DISCONNECTED component="
                            + name.flattenToShortString());
                }
            };
            if (!bindService(crossPackageServiceIntent, crossPackageConnection, BIND_AUTO_CREATE)) {
                throw new AssertionError("CROSS_SERVICE_BIND_RETURNED_FALSE");
            }
            Intent directReceiverIntent = new Intent(
                    "com.warden.controlledsandbox.fixture32.DIRECT_FRAMEWORK_RECEIVER_PROBE")
                    .setComponent(new ComponentName(peerPackage,
                            "com.warden.controlledsandbox.fixture.FixtureReceiver"))
                    .putExtra("frameworkReceiverValue", "cross-package-activity-thread");
            sendBroadcast(directReceiverIntent);
            Log.i(TAG, "FRAMEWORK_PROBE_CROSS_RECEIVER_FRAMEWORK_REQUESTED component="
                    + directReceiverIntent.getComponent().flattenToShortString());
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) pendingFlags |= PendingIntent.FLAG_MUTABLE;
            else if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            Intent crossReceiverIntent = new Intent("com.warden.controlledsandbox.fixture32.PING")
                    .setComponent(new ComponentName(peerPackage,
                            "com.warden.controlledsandbox.fixture.FixtureReceiver"));
            PendingIntent crossReceiverPending = PendingIntent.getBroadcast(
                    this, 258, crossReceiverIntent, pendingFlags);
            if (crossReceiverPending == null) {
                throw new AssertionError("CROSS_PENDING_INTENT_NULL");
            }
            crossReceiverPending.send();
            Log.i(TAG, "FRAMEWORK_PROBE_CROSS_PENDING_INTENT_PASS component="
                    + crossReceiverIntent.getComponent().flattenToShortString());
            Log.i(TAG, "FRAMEWORK_PROBE_CROSS_ROUTE_REQUESTED package=" + peerPackage);
        } catch (Exception error) {
            throw new AssertionError("CROSS_PACKAGE_COMPONENT_FAILED:" + error, error);
        }
    }

    private void finishProbe() {
        if (!serviceConnected) throw new AssertionError("SERVICE_BIND_CALLBACK_MISSING");
        if (!crossPackageServiceConnected) {
            throw new AssertionError("CROSS_PACKAGE_SERVICE_BIND_CALLBACK_MISSING");
        }
        if (remoteServiceIntent != null && !stopService(remoteServiceIntent)) {
            throw new AssertionError("REMOTE_SERVICE_STOP_FAILED");
        }
        Log.i(TAG, "FRAMEWORK_PROBE_REMOTE_STOP_PASS");
        if (crossPackageServiceIntent != null && !stopService(crossPackageServiceIntent)) {
            throw new AssertionError("CROSS_PACKAGE_SERVICE_STOP_FAILED");
        }
        Log.i(TAG, "FRAMEWORK_PROBE_CROSS_STOP_PASS");
        if (connection != null) {
            unbindService(connection);
            connection = null;
        }
        if (crossPackageConnection != null) {
            unbindService(crossPackageConnection);
            crossPackageConnection = null;
        }
        Log.i(TAG, "FRAMEWORK_PROBE_PASS");
        finish();
    }

    @Override protected void onDestroy() {
        if (dynamicReceiver != null) {
            try { unregisterReceiver(dynamicReceiver); } catch (RuntimeException ignored) { }
            dynamicReceiver = null;
        }
        if (connection != null) {
            try { unbindService(connection); } catch (RuntimeException ignored) { }
            connection = null;
        }
        if (crossPackageConnection != null) {
            try { unbindService(crossPackageConnection); } catch (RuntimeException ignored) { }
            crossPackageConnection = null;
        }
        super.onDestroy();
    }
}
