package com.warden.controlledsandbox;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import com.warden.controlledsandbox.runtime.broker.RuntimePackageAuthorityBootstrapService;

/** Owns Package Service outbound bindings to fixed, non-exported trusted process endpoints. */
final class PackageAuthorityBootstrapConnections implements AutoCloseable {
    private final Context context;
    private final PackageAuthorityCapabilityRegistry registry;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RoleConnection management;
    private final RoleConnection runtime;
    private boolean closed;

    PackageAuthorityBootstrapConnections(Context context,
            PackageAuthorityCapabilityRegistry registry) {
        this.context = context.getApplicationContext();
        this.registry = registry;
        management = new RoleConnection(
                new Intent(this.context, HostPackageAuthorityBootstrapService.class), true);
        runtime = new RoleConnection(
                new Intent(this.context, RuntimePackageAuthorityBootstrapService.class), false);
    }

    void start() {
        management.bind();
        runtime.bind();
    }

    @Override public void close() {
        closed = true;
        management.close();
        runtime.close();
    }

    private final class RoleConnection implements ServiceConnection {
        private final Intent intent;
        private final boolean managementRole;
        private IBinder capability;
        private boolean bound;
        private boolean rebindPosted;

        RoleConnection(Intent intent, boolean managementRole) {
            this.intent = intent;
            this.managementRole = managementRole;
        }

        void bind() {
            if (closed || bound) return;
            try { bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE); }
            catch (RuntimeException ignored) { bound = false; }
            if (!bound) scheduleRebind();
        }

        @Override public void onServiceConnected(ComponentName name, IBinder value) {
            rebindPosted = false;
            if (closed || value == null || !value.isBinderAlive()) {
                scheduleRebind();
                return;
            }
            capability = value;
            if (managementRole) registry.installManagement(value);
            else registry.installRuntime(value);
        }

        @Override public void onServiceDisconnected(ComponentName name) { disconnected(); }
        @Override public void onBindingDied(ComponentName name) { disconnected(); }
        @Override public void onNullBinding(ComponentName name) { disconnected(); }

        private void disconnected() {
            IBinder stale = capability;
            capability = null;
            if (stale != null) {
                if (managementRole) registry.clearManagement(stale);
                else registry.clearRuntime(stale);
            }
            unbind();
            scheduleRebind();
        }

        private void scheduleRebind() {
            if (closed || rebindPosted) return;
            rebindPosted = true;
            handler.post(() -> {
                rebindPosted = false;
                bind();
            });
        }

        private void unbind() {
            if (!bound) return;
            bound = false;
            try { context.unbindService(this); }
            catch (RuntimeException ignored) { }
        }

        void close() {
            IBinder stale = capability;
            capability = null;
            if (stale != null) {
                if (managementRole) registry.clearManagement(stale);
                else registry.clearRuntime(stale);
            }
            unbind();
        }
    }
}
