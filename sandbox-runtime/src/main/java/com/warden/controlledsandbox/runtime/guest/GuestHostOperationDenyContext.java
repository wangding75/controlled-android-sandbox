package com.warden.controlledsandbox.runtime.guest;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import java.util.concurrent.Executor;

/** Deny-by-default boundary for Host identity-bearing Context operations. */
abstract class GuestHostOperationDenyContext extends ContextWrapper {
    /**
     * Keep the ContextWrapper base detached. Any Android API overload that is added in a newer
     * SDK and is not yet explicitly handled here therefore fails closed instead of delegating to
     * the Host Context. GuestContext keeps the Host transport in a private field for the small
     * allowlisted surface it intentionally exposes.
     */
    GuestHostOperationDenyContext() { super(null); }

    @Override public boolean bindService(Intent service, ServiceConnection connection, int flags) {
        throw deniedHostOperation("bindService");
    }
    @Override public boolean bindService(Intent service, int flags, Executor executor,
            ServiceConnection connection) {
        throw deniedHostOperation("bindService");
    }
    @Override public boolean bindIsolatedService(Intent service, int flags, String instanceName,
            Executor executor, ServiceConnection connection) {
        throw deniedHostOperation("bindIsolatedService");
    }
    @Override public void updateServiceGroup(
            ServiceConnection connection, int group, int importance) {
        throw deniedHostOperation("updateServiceGroup");
    }
    @Override public void unbindService(ServiceConnection connection) {
        throw deniedHostOperation("unbindService");
    }
    @Override public ComponentName startService(Intent service) {
        throw deniedHostOperation("startService");
    }
    @Override public ComponentName startForegroundService(Intent service) {
        throw deniedHostOperation("startForegroundService");
    }
    @Override public boolean stopService(Intent service) {
        throw deniedHostOperation("stopService");
    }
    @Override public void sendBroadcast(Intent intent) {
        throw deniedHostOperation("sendBroadcast");
    }
    @Override public void sendBroadcast(Intent intent, String receiverPermission) {
        throw deniedHostOperation("sendBroadcast");
    }
    @Override public void sendBroadcast(
            Intent intent, String receiverPermission, Bundle options) {
        throw deniedHostOperation("sendBroadcast");
    }
    @Override public void sendBroadcastAsUser(Intent intent, UserHandle user) {
        throw deniedHostOperation("sendBroadcastAsUser");
    }
    @Override public void sendOrderedBroadcast(Intent intent, String receiverPermission) {
        throw deniedHostOperation("sendOrderedBroadcast");
    }
    @Override public void sendOrderedBroadcast(Intent intent, String receiverPermission,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        throw deniedHostOperation("sendOrderedBroadcast");
    }
    @Override public void sendOrderedBroadcast(Intent intent, String receiverPermission,
            Bundle options, BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        throw deniedHostOperation("sendOrderedBroadcast");
    }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        throw deniedHostOperation("registerReceiver");
    }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            int flags) {
        throw deniedHostOperation("registerReceiver");
    }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        throw deniedHostOperation("registerReceiver");
    }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler, int flags) {
        throw deniedHostOperation("registerReceiver");
    }
    @Override public void unregisterReceiver(BroadcastReceiver receiver) {
        throw deniedHostOperation("unregisterReceiver");
    }
    @Override public ContentResolver getContentResolver() {
        throw deniedHostOperation("getContentResolver");
    }
    @Override public PackageManager getPackageManager() {
        throw deniedHostOperation("getPackageManager");
    }
    @Override public int checkPermission(String permission, int pid, int uid) {
        throw deniedHostOperation("checkPermission");
    }
    @Override public int checkCallingPermission(String permission) {
        throw deniedHostOperation("checkCallingPermission");
    }
    @Override public int checkCallingOrSelfPermission(String permission) {
        throw deniedHostOperation("checkCallingOrSelfPermission");
    }
    @Override public int checkSelfPermission(String permission) {
        throw deniedHostOperation("checkSelfPermission");
    }
    @Override public void enforcePermission(String permission, int pid, int uid, String message) {
        throw deniedHostOperation("enforcePermission");
    }
    @Override public void grantUriPermission(String toPackage, Uri uri, int modeFlags) {
        throw deniedHostOperation("grantUriPermission");
    }
    @Override public void revokeUriPermission(Uri uri, int modeFlags) {
        throw deniedHostOperation("revokeUriPermission");
    }
    @Override public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        throw deniedHostOperation("checkUriPermission");
    }
    @Override public void startActivity(Intent intent) {
        throw deniedHostOperation("startActivity");
    }
    @Override public void startActivity(Intent intent, Bundle options) {
        throw deniedHostOperation("startActivity");
    }
    @Override public void startActivities(Intent[] intents) {
        throw deniedHostOperation("startActivities");
    }
    @Override public void startActivities(Intent[] intents, Bundle options) {
        throw deniedHostOperation("startActivities");
    }
    @Override public void startIntentSender(IntentSender intent, Intent fillInIntent,
            int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        throw deniedHostOperation("startIntentSender");
    }
    @Override public void startIntentSender(IntentSender intent, Intent fillInIntent,
            int flagsMask, int flagsValues, int extraFlags, Bundle options)
            throws IntentSender.SendIntentException {
        throw deniedHostOperation("startIntentSender");
    }
    @Override public Executor getMainExecutor() {
        throw deniedHostOperation("getMainExecutor");
    }

    private static SecurityException deniedHostOperation(String operation) {
        return new SecurityException("GUEST_CONTEXT_HOST_OPERATION_DENIED:" + operation);
    }
}
