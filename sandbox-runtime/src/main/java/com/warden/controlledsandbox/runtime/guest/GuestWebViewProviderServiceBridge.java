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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Binds the small, explicit platform-service surface required by the selected WebView provider.
 * The provider is selected by the virtual WebView profile; no arbitrary package or service is
 * accepted, and ordinary Guest component routing remains deny-first.
 */
final class GuestWebViewProviderServiceBridge implements AutoCloseable {
    private static final AtomicInteger WORKER_IDS = new AtomicInteger();
    private final Context hostServiceContext;
    private final Map<ServiceConnection, ServiceConnection> bindings = new IdentityHashMap<>();
    private final java.util.Set<ServiceConnection> failedBindings =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final ExecutorService bindWorkers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable,
                "guest-webview-bind-" + WORKER_IDS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
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
        failedBindings.remove(connection);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Host Context.bindService() can synchronously wait for AMS/provider process state.
            // This path is used before the ordinary Guest service router (notably for Chromium
            // renderer services), so leaving it on the Guest main Looper recreates the same
            // input-dispatch starvation as a synchronous Broker call. Reserve the connection
            // before handing the whole host bind to a worker; callbacks still use the original
            // ServiceConnection executor through ProviderServiceRelay.
            bindings.put(connection, relay);
            try {
                bindWorkers.execute(() -> completeBind(copy, connection, relay, flags, null,
                        false));
            } catch (Throwable error) {
                bindings.remove(connection, relay);
                failedBindings.add(connection);
                relay.close();
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                        .rethrowIfFatal(error);
                throw error instanceof RuntimeException
                        ? (RuntimeException) error : new IllegalStateException(error);
            }
            android.util.Log.i("CS_WEBVIEW_PROVIDER", "bind-queued="
                    + copy.getComponent().flattenToShortString());
            return true;
        }
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
        failedBindings.remove(connection);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // bindIsolatedService() has the same synchronous AMS/provider boundary as the
            // ordinary bind above. Keep the Guest main queue free while the renderer process is
            // resolved and started.
            bindings.put(connection, relay);
            try {
                bindWorkers.execute(() -> completeBind(copy, connection, relay, flags, instanceName,
                        true));
            } catch (Throwable error) {
                bindings.remove(connection, relay);
                failedBindings.add(connection);
                relay.close();
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                        .rethrowIfFatal(error);
                throw error instanceof RuntimeException
                        ? (RuntimeException) error : new IllegalStateException(error);
            }
            android.util.Log.i("CS_WEBVIEW_PROVIDER", "isolated-bind-queued="
                    + copy.getComponent().flattenToShortString());
            return true;
        }
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

    boolean unbind(ServiceConnection connection) {
        ServiceConnection relay;
        synchronized (this) {
            relay = bindings.remove(connection);
            if (relay == null) return failedBindings.remove(connection);
        }
        if (relay instanceof ProviderServiceRelay providerRelay) providerRelay.close();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                bindWorkers.execute(() -> unbindHost(relay, true));
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                        .rethrowIfFatal(error);
                android.util.Log.w("CS_WEBVIEW_PROVIDER", "async unbind queue rejected", error);
            }
        } else {
            unbindHost(relay, false);
        }
        return true;
    }

    @Override public void close() {
        java.util.ArrayList<ServiceConnection> relays;
        synchronized (this) {
            if (closed) return;
            closed = true;
            relays = new java.util.ArrayList<>(bindings.values());
            bindings.clear();
            failedBindings.clear();
        }
        for (ServiceConnection relay : relays) {
            if (relay instanceof ProviderServiceRelay providerRelay) providerRelay.close();
            if (Looper.myLooper() == Looper.getMainLooper()) {
                try { bindWorkers.execute(() -> unbindHost(relay, true)); }
                catch (RuntimeException ignored) { }
            } else {
                unbindHost(relay, true);
            }
        }
        bindWorkers.shutdown();
    }

    private void completeBind(Intent intent, ServiceConnection connection,
                              ProviderServiceRelay relay, int flags, String instanceName,
                              boolean isolated) {
        boolean bound = false;
        Throwable failure = null;
        try {
            bound = isolated
                    ? hostServiceContext.bindIsolatedService(intent, flags, instanceName,
                            mainExecutor(), relay)
                    : hostServiceContext.bindService(intent, relay, flags);
        } catch (Throwable error) {
            failure = error;
        }
        boolean publish;
        synchronized (this) {
            publish = bound && !closed && bindings.get(connection) == relay;
            if (!publish) {
                bindings.remove(connection, relay);
                if (!bound) failedBindings.add(connection);
            }
        }
        if (failure != null) {
            relay.close();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                    .rethrowIfFatal(failure);
            android.util.Log.e("CS_WEBVIEW_PROVIDER", "async host bind failed component="
                    + intent.getComponent(), failure);
            return;
        }
        if (!bound) {
            relay.close();
            android.util.Log.w("CS_WEBVIEW_PROVIDER", "async host bind rejected component="
                    + intent.getComponent());
            return;
        }
        if (!publish) {
            relay.close();
            unbindHost(relay, true);
            return;
        }
        android.util.Log.i("CS_WEBVIEW_PROVIDER", (isolated ? "isolated-bound=" : "bound=")
                + intent.getComponent().flattenToShortString());
    }

    private void unbindHost(ServiceConnection relay, boolean quiet) {
        try {
            hostServiceContext.unbindService(relay);
        } catch (RuntimeException error) {
            if (!quiet) throw error;
            android.util.Log.i("CS_WEBVIEW_PROVIDER", "late host unbind ignored", error);
        }
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
