package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.framework.contract.WebViewProviderServiceContract;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Binds the small, explicit platform-service surface required by the selected WebView provider.
 * The provider is selected by the virtual WebView profile; no arbitrary package or service is
 * accepted, and ordinary Guest component routing remains deny-first.
 */
final class GuestWebViewProviderServiceBridge implements AutoCloseable {
    private final Context hostServiceContext;
    private final Map<ServiceConnection, ServiceConnection> bindings = new IdentityHashMap<>();
    private String providerPackage = "";

    GuestWebViewProviderServiceBridge(Context hostServiceContext) {
        this.hostServiceContext = java.util.Objects.requireNonNull(hostServiceContext,
                "hostServiceContext");
    }

    synchronized void configure(String providerPackage) {
        if (providerPackage == null || providerPackage.trim().isEmpty()) {
            throw new IllegalArgumentException("WebView provider package is required");
        }
        if (!WebViewProviderServiceContract.isProviderPackage(providerPackage)) {
            throw new SecurityException("VIRTUAL_WEBVIEW_PROVIDER_NOT_ALLOWLISTED:" + providerPackage);
        }
        if (!this.providerPackage.isEmpty() && !this.providerPackage.equals(providerPackage)) {
            throw new IllegalStateException("WEBVIEW_PROVIDER_RECONFIGURATION");
        }
        this.providerPackage = providerPackage;
    }

    synchronized boolean bind(Intent intent, ServiceConnection connection, int flags,
            Executor executor) {
        if (!isAllowed(intent)) return false;
        if (connection == null) throw new IllegalArgumentException("connection is required");
        if (bindings.containsKey(connection)) throw new IllegalArgumentException(
                "ServiceConnection already bound");
        ServiceConnection relay = relay(connection, executor);
        Intent copy = new Intent(intent);
        if (!hostServiceContext.bindService(copy, relay, flags)) return false;
        bindings.put(connection, relay);
        android.util.Log.i("CS_WEBVIEW_PROVIDER", "bound="
                + copy.getComponent().flattenToShortString());
        return true;
    }

    synchronized boolean bindIsolated(Intent intent, int flags, String instanceName,
            Executor executor, ServiceConnection connection) {
        if (!isRendererService(intent)) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        if (connection == null) throw new IllegalArgumentException("connection is required");
        if (bindings.containsKey(connection)) throw new IllegalArgumentException(
                "ServiceConnection already bound");
        if (instanceName != null && instanceName.length() > 128) {
            throw new IllegalArgumentException("WebView renderer instance name is too long");
        }
        ServiceConnection relay = relay(connection, executor);
        Intent copy = new Intent(intent);
        // The Host Context is used only as the Android transport. The component and provider
        // package were checked above; the Guest callback is still dispatched on its Executor.
        boolean bound = hostServiceContext.bindIsolatedService(copy, flags, instanceName,
                mainExecutor(), relay);
        if (!bound) return false;
        bindings.put(connection, relay);
        android.util.Log.i("CS_WEBVIEW_PROVIDER", "isolated-bound="
                + copy.getComponent().flattenToShortString());
        return true;
    }

    synchronized boolean unbind(ServiceConnection connection) {
        ServiceConnection relay = bindings.remove(connection);
        if (relay == null) return false;
        hostServiceContext.unbindService(relay);
        return true;
    }

    @Override public synchronized void close() {
        for (ServiceConnection relay : bindings.values()) {
            try { hostServiceContext.unbindService(relay); }
            catch (RuntimeException ignored) { }
        }
        bindings.clear();
    }

    private boolean isAllowed(Intent intent) {
        if (intent == null || intent.getComponent() == null) return false;
        ComponentName component = intent.getComponent();
        return WebViewProviderServiceContract.isProviderService(providerPackage, component);
    }

    private boolean isRendererService(Intent intent) {
        if (intent == null || intent.getComponent() == null) return false;
        return WebViewProviderServiceContract.isRendererService(providerPackage,
                intent.getComponent());
    }

    private static ServiceConnection relay(ServiceConnection connection, Executor executor) {
        return new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                dispatch(executor, () -> connection.onServiceConnected(name, service));
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                dispatch(executor, () -> connection.onServiceDisconnected(name));
            }

            @Override public void onBindingDied(ComponentName name) {
                dispatch(executor, () -> connection.onBindingDied(name));
            }

            @Override public void onNullBinding(ComponentName name) {
                dispatch(executor, () -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        connection.onNullBinding(name);
                    } else {
                        connection.onServiceDisconnected(name);
                    }
                });
            }
        };
    }

    private Executor mainExecutor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return hostServiceContext.getMainExecutor();
        }
        Handler handler = new Handler(Looper.getMainLooper());
        return handler::post;
    }

    private static void dispatch(Executor executor, Runnable callback) {
        if (executor == null) callback.run();
        else executor.execute(callback);
    }
}
