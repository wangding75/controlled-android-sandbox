package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.util.List;

/** Focused Guest-Context/PMS probe for P1-05; no synthetic callback or host fallback is used. */
public final class P105ServiceSemanticsProbeActivity extends Activity {
    private static final String TAG = "CS_P1_05_FIXTURE";
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean connected;
    private boolean nullBinding;
    private boolean disconnected;
    private boolean bindingDied;
    private ServiceConnection normal;
    private ServiceConnection nullService;
    private ServiceConnection dying;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            verifyPackageManagerSurface();
            verifyMissingContextReturns();
            if (getIntent().getBooleanExtra("p105CasMode", false)) {
                verifyPeerServiceDenials();
            }
            bindRealServices();
            main.postDelayed(this::finishWhenCallbacksDelivered, 5_000L);
        } catch (Throwable error) {
            throw new AssertionError("P1_05_SERVICE_SEMANTICS_FAILED", error);
        }
    }

    private void verifyPackageManagerSurface() throws Exception {
        PackageManager pm = getPackageManager();
        ComponentName normalComponent = new ComponentName(this, FixtureService.class);
        Intent normalIntent = new Intent(this, FixtureService.class)
                .setAction(getPackageName() + ".P1_05_NORMAL");
        ResolveInfo resolved = pm.resolveService(normalIntent, 0);
        List<ResolveInfo> queried = pm.queryIntentServices(normalIntent, 0);
        ServiceInfo info = pm.getServiceInfo(normalComponent, 0);
        if (resolved == null || resolved.serviceInfo == null || queried == null
                || queried.isEmpty() || info == null || !getPackageName().equals(info.packageName)
                || info.processName == null || info.processName.isEmpty()) {
            throw new AssertionError("P1_05_VIRTUAL_PM_SURFACE_MISMATCH");
        }
        Log.i(TAG, "P1_05_VIRTUAL_PM_PASS component=" + normalComponent.flattenToShortString()
                + " process=" + info.processName + " queryCount=" + queried.size());
    }

    private void verifyMissingContextReturns() {
        ComponentName missingComponent = new ComponentName(getPackageName(),
                getPackageName() + ".P105MissingService");
        Intent missing = new Intent().setComponent(missingComponent)
                .setAction(getPackageName() + ".P1_05_MISSING");
        PackageManager pm = getPackageManager();
        if (pm.resolveService(missing, 0) != null || !pm.queryIntentServices(missing, 0).isEmpty()) {
            throw new AssertionError("P1_05_MISSING_PM_NOT_ABSENT");
        }
        try {
            pm.getServiceInfo(missingComponent, 0);
            throw new AssertionError("P1_05_MISSING_SERVICE_INFO_NOT_ABSENT");
        } catch (PackageManager.NameNotFoundException expected) {
            // Public PackageManager translates the virtual null to the platform checked shape.
        }
        ServiceConnection missingConnection = new EmptyConnection();
        if (bindService(missing, missingConnection, BIND_AUTO_CREATE)) {
            throw new AssertionError("P1_05_MISSING_BIND_ACCEPTED");
        }
        if (startService(missing) != null || stopService(missing)) {
            throw new AssertionError("P1_05_MISSING_CONTEXT_RETURN_MISMATCH");
        }
        Log.i(TAG, "P1_05_MISSING_RETURNS_PASS bind=false start=null stop=false");
    }

    /** Only CAS runs this: platform PMS may expose an explicit peer component differently. */
    private void verifyPeerServiceDenials() throws Exception {
        verifyPeerServiceDenied(RemoteFixtureService.class.getName(),
                "VIRTUAL_SERVICE_NOT_EXPORTED", "not_exported");
        verifyPeerServiceDenied(P105PeerPermissionService.class.getName(),
                "VIRTUAL_SERVICE_PERMISSION_DENIED", "permission");
        Log.i(TAG, "P1_05_PEER_DENIED_PASS nonExported=security permission=security");
    }

    private void verifyPeerServiceDenied(String className, String expectedError, String label)
            throws Exception {
        String peerPackage = "com.warden.controlledsandbox.fixture32";
        ComponentName component = new ComponentName(peerPackage, className);
        Intent intent = new Intent().setComponent(component)
                .setAction(getPackageName() + ".P1_05_PEER_" + label);
        PackageManager pm = getPackageManager();
        ServiceInfo info = pm.getServiceInfo(component, 0);
        if (info == null || !peerPackage.equals(info.packageName)) {
            throw new AssertionError("P1_05_PEER_INFO_MISSING_" + label);
        }
        if (pm.resolveService(intent, 0) != null || !pm.queryIntentServices(intent, 0).isEmpty()) {
            throw new AssertionError("P1_05_PEER_PM_EXPOSED_" + label);
        }
        try {
            bindService(intent, new EmptyConnection(), BIND_AUTO_CREATE);
            throw new AssertionError("P1_05_PEER_BIND_ACCEPTED_" + label);
        } catch (SecurityException expected) {
            if (!expectedError.equals(expected.getMessage())) {
                throw new AssertionError("P1_05_PEER_WRONG_DENIAL_" + label, expected);
            }
        }
    }

    private void bindRealServices() {
        normal = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (binder == null) throw new AssertionError("P1_05_CONNECTED_NULL_BINDER");
                connected = true;
                Log.i(TAG, "P1_05_ON_CONNECTED component=" + name.flattenToShortString());
                unbindQuietly(this);
                main.postDelayed(P105ServiceSemanticsProbeActivity.this::bindNullService, 150L);
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        if (!bindService(new Intent(this, FixtureService.class), BIND_AUTO_CREATE,
                getMainExecutor(), normal)) {
            throw new AssertionError("P1_05_NORMAL_BIND_FALSE");
        }
    }

    private void bindNullService() {
        nullService = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                throw new AssertionError("P1_05_NULL_SERVICE_CONNECTED");
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
            @Override public void onNullBinding(ComponentName name) {
                nullBinding = true;
                Log.i(TAG, "P1_05_ON_NULL_BINDING component=" + name.flattenToShortString());
                unbindQuietly(this);
                main.postDelayed(P105ServiceSemanticsProbeActivity.this::bindDyingService, 150L);
            }
        };
        if (!bindService(new Intent(this, P105NullBindingService.class), BIND_AUTO_CREATE,
                getMainExecutor(), nullService)) {
            throw new AssertionError("P1_05_NULL_BIND_FALSE");
        }
    }

    private void bindDyingService() {
        dying = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                Log.i(TAG, "P1_05_DYING_CONNECTED component=" + name.flattenToShortString());
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                disconnected = true;
                Log.i(TAG, "P1_05_ON_DISCONNECTED component=" + name.flattenToShortString());
            }
            @Override public void onBindingDied(ComponentName name) {
                bindingDied = true;
                Log.i(TAG, "P1_05_ON_BINDING_DIED component=" + name.flattenToShortString());
            }
        };
        if (!bindService(new Intent(this, P105DyingService.class), BIND_AUTO_CREATE,
                getMainExecutor(), dying)) {
            throw new AssertionError("P1_05_DYING_BIND_FALSE");
        }
    }

    private void finishWhenCallbacksDelivered() {
        if (!connected || !nullBinding || (!disconnected && !bindingDied)) {
            throw new AssertionError("P1_05_CALLBACKS_MISSING connected=" + connected
                    + " nullBinding=" + nullBinding + " disconnected=" + disconnected
                    + " bindingDied=" + bindingDied);
        }
        unbindQuietly(dying);
        Log.i(TAG, "P1_05_SERVICE_SEMANTICS_PASS connected=" + connected
                + " nullBinding=" + nullBinding + " disconnected=" + disconnected
                + " bindingDied=" + bindingDied + " cleanup=true");
        finish();
    }

    private void unbindQuietly(ServiceConnection connection) {
        try { unbindService(connection); } catch (RuntimeException ignored) { }
    }

    @Override protected void onDestroy() {
        unbindQuietly(normal);
        unbindQuietly(nullService);
        unbindQuietly(dying);
        super.onDestroy();
    }

    private static final class EmptyConnection implements ServiceConnection {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { }
        @Override public void onServiceDisconnected(ComponentName name) { }
    }
}
