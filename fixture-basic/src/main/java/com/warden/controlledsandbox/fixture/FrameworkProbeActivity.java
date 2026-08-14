package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** Guest-side API probe for the RD API32 component and transport gates. */
public final class FrameworkProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private boolean serviceConnected;
    private ServiceConnection connection;
    private Intent remoteServiceIntent;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(TAG, "FRAMEWORK_PROBE_BEGIN package=" + getPackageName());
        providerBatchProbe();
        pendingIntentProbe();
        serviceBindingProbe();
        packageUniverseProbe();
        multiProcessProbe();
        new Handler(Looper.getMainLooper()).postDelayed(this::finishProbe, 1500L);
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
        try {
            ContentProviderResult[] results = resolver.applyBatch(uri.getAuthority(), operations);
            if (results == null || results.length != operations.size()) {
                throw new AssertionError("PROVIDER_BATCH_RESULT_COUNT");
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
            List<ResolveInfo> launchers = getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            boolean launcherVisible = false;
            for (ResolveInfo value : launchers) {
                if (value.activityInfo != null && peerPackage.equals(value.activityInfo.packageName)) {
                    launcherVisible = true;
                    break;
                }
            }
            if (!peerPackage.equals(peer.packageName) || !peerPackage.equals(packageInfo.packageName)
                    || peerProvider == null || !peerPackage.equals(peerProvider.packageName)
                    || !launcherVisible) {
                throw new AssertionError("VIRTUAL_PACKAGE_UNIVERSE_MISMATCH");
            }
            Log.i(TAG, "FRAMEWORK_PROBE_PACKAGE_UNIVERSE_PASS package=" + peerPackage
                    + " launchers=" + launchers.size());
        } catch (Exception error) {
            throw new AssertionError("VIRTUAL_PACKAGE_UNIVERSE_FAILED", error);
        }
    }

    private void finishProbe() {
        if (!serviceConnected) throw new AssertionError("SERVICE_BIND_CALLBACK_MISSING");
        if (remoteServiceIntent != null && !stopService(remoteServiceIntent)) {
            throw new AssertionError("REMOTE_SERVICE_STOP_FAILED");
        }
        Log.i(TAG, "FRAMEWORK_PROBE_REMOTE_STOP_PASS");
        if (connection != null) {
            unbindService(connection);
            connection = null;
        }
        Log.i(TAG, "FRAMEWORK_PROBE_PASS");
        finish();
    }

    @Override protected void onDestroy() {
        if (connection != null) {
            try { unbindService(connection); } catch (RuntimeException ignored) { }
            connection = null;
        }
        super.onDestroy();
    }
}
