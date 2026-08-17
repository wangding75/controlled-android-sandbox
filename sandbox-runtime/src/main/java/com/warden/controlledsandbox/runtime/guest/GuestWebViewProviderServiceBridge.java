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
    /**
     * Session teardown is allowed to race a provider callback.  The host callback is Binder
     * ordered, but the WebView bridge deliberately hands it to an arbitrary Executor; without
     * an explicit fence a queued callback can re-enter Chromium after the Guest generation has
     * already been retired.  This is the same ownership boundary used by the ordinary Guest
     * Service relay.
     */
    private volatile boolean closed;
    private String providerPackage = "";

    GuestWebViewProviderServiceBridge(Context hostServiceContext) {
        this.hostServiceContext = java.util.Objects.requireNonNull(hostServiceContext,
                "hostServiceContext");
    }

    synchronized void configure(String providerPackage) {
        if (closed) throw new IllegalStateException("WEBVIEW_PROVIDER_BRIDGE_CLOSED");
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
        if (closed) throw new IllegalStateException("WEBVIEW_PROVIDER_BRIDGE_CLOSED");
        if (!isAllowed(intent)) return false;
        if (connection == null) throw new IllegalArgumentException("connection is required");
        if (bindings.containsKey(connection)) throw new IllegalArgumentException(
                "ServiceConnection already bound");
        ProviderServiceRelay relay = relay(connection, executor);
        Intent copy = new Intent(intent);
        if (!hostServiceContext.bindService(copy, relay, flags)) {
            relay.close();
            return false;
        }
        if (closed) {
            relay.close();
            try { hostServiceContext.unbindService(relay); }
            catch (RuntimeException ignored) { }
            return false;
        }
        bindings.put(connection, relay);
        android.util.Log.i("CS_WEBVIEW_PROVIDER", "bound="
                + copy.getComponent().flattenToShortString());
        return true;
    }

    synchronized boolean bindIsolated(Intent intent, int flags, String instanceName,
            Executor executor, ServiceConnection connection) {
        if (closed) throw new IllegalStateException("WEBVIEW_PROVIDER_BRIDGE_CLOSED");
        if (!isRendererService(intent)) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        if (connection == null) throw new IllegalArgumentException("connection is required");
        if (bindings.containsKey(connection)) throw new IllegalArgumentException(
                "ServiceConnection already bound");
        if (instanceName != null && instanceName.length() > 128) {
            throw new IllegalArgumentException("WebView renderer instance name is too long");
        }
        ProviderServiceRelay relay = relay(connection, executor);
        Intent copy = new Intent(intent);
        // The Host Context is used only as the Android transport. The component and provider
        // package were checked above; the Guest callback is still dispatched on its Executor.
        boolean bound = hostServiceContext.bindIsolatedService(copy, flags, instanceName,
                mainExecutor(), relay);
        if (!bound) {
            relay.close();
            return false;
        }
        if (closed) {
            relay.close();
            try { hostServiceContext.unbindService(relay); }
            catch (RuntimeException ignored) { }
            return false;
        }
        bindings.put(connection, relay);
        android.util.Log.i("CS_WEBVIEW_PROVIDER", "isolated-bound="
                + copy.getComponent().flattenToShortString());
        return true;
    }

    synchronized boolean unbind(ServiceConnection connection) {
        ServiceConnection relay = bindings.remove(connection);
        if (relay == null) return false;
        if (relay instanceof ProviderServiceRelay providerRelay) providerRelay.close();
        hostServiceContext.unbindService(relay);
        return true;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (ServiceConnection relay : bindings.values()) {
            if (relay instanceof ProviderServiceRelay providerRelay) providerRelay.close();
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

    private static ProviderServiceRelay relay(ServiceConnection connection, Executor executor) {
        return new ProviderServiceRelay(connection, executor);
    }

    /** Package-visible for the deterministic callback-fence self-test. */
    static final class ProviderServiceRelay implements ServiceConnection {
        private final ServiceConnection connection;
        private final Executor executor;
        private volatile boolean closed;

        ProviderServiceRelay(ServiceConnection connection, Executor executor) {
            this.connection = connection;
            this.executor = executor;
        }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            dispatch(() -> connection.onServiceConnected(name, service));
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            dispatch(() -> connection.onServiceDisconnected(name));
        }

        @Override public void onBindingDied(ComponentName name) {
            dispatch(() -> connection.onBindingDied(name));
        }

        @Override public void onNullBinding(ComponentName name) {
            dispatch(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    connection.onNullBinding(name);
                } else {
                    connection.onServiceDisconnected(name);
                }
            });
        }

        void close() { closed = true; }

        private void dispatch(Runnable callback) {
            if (closed) return;
            Runnable guarded = () -> {
                if (closed) return;
                callback.run();
            };
            try {
                if (executor == null) guarded.run();
                else executor.execute(guarded);
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                close();
                android.util.Log.e("CS_WEBVIEW_PROVIDER",
                        "provider callback executor rejected", error);
            }
        }
    }

    private Executor mainExecutor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return hostServiceContext.getMainExecutor();
        }
        Handler handler = new Handler(Looper.getMainLooper());
        return handler::post;
    }

}
