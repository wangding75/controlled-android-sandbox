package com.warden.controlledsandbox;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import com.warden.controlledsandbox.contract.IPackageAuthorityBootstrap;
import com.warden.controlledsandbox.runtime.protocol.RuntimePackageAuthorityBootstrapService;

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

    String diagnosticState() {
        return management.diagnosticState() + ";" + runtime.diagnosticState();
    }

    private final class RoleConnection implements ServiceConnection {
        private final Intent intent;
        private final boolean managementRole;
        private final PackageAuthorityRetryBackoff retryBackoff;
        private final Runnable rebindTask = () -> {
            rebindPosted = false;
            scheduledDelayMs = 0L;
            bind();
        };
        private IBinder capability;
        private boolean bound;
        private boolean rebindPosted;
        private long scheduledDelayMs;

        RoleConnection(Intent intent, boolean managementRole) {
            this.intent = intent;
            this.managementRole = managementRole;
            this.retryBackoff = new PackageAuthorityRetryBackoff(managementRole ? 0x4d474d54 : 0x52554e54);
        }

        void bind() {
            if (closed || bound) return;
            try { bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE); }
            catch (RuntimeException ignored) { bound = false; }
            if (!bound) scheduleRebind();
        }

        @Override public void onServiceConnected(ComponentName name, IBinder value) {
            rebindPosted = false;
            try {
                IPackageAuthorityBootstrap endpoint =
                        IPackageAuthorityBootstrap.Stub.asInterface(value);
                if (closed || endpoint == null || value == null || !value.isBinderAlive()) {
                    disconnected();
                    return;
                }
                IBinder installed = endpoint.capability();
                int ownerPid = endpoint.ownerPid();
                if (installed == null || !installed.isBinderAlive() || ownerPid <= 0) {
                    disconnected();
                    return;
                }
                capability = installed;
                if (managementRole) {
                    registry.installManagement(installed, Process.myUid(), ownerPid);
                } else {
                    registry.installRuntime(installed, Process.myUid(), ownerPid);
                }
                retryBackoff.reset();
                scheduledDelayMs = 0L;
            } catch (Exception bootstrapFailure) {
                disconnected();
            }
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
            scheduledDelayMs = retryBackoff.nextDelayMillis();
            if (!handler.postDelayed(rebindTask, scheduledDelayMs)) {
                rebindPosted = false;
                scheduledDelayMs = 0L;
            }
        }

        String diagnosticState() {
            return (managementRole ? "management" : "runtime")
                    + ":bound=" + bound
                    + ":failures=" + retryBackoff.consecutiveFailures()
                    + ":circuitOpen=" + retryBackoff.circuitOpen()
                    + ":scheduledDelayMs=" + scheduledDelayMs;
        }

        private void unbind() {
            if (!bound) return;
            bound = false;
            try { context.unbindService(this); }
            catch (RuntimeException ignored) { }
        }

        void close() {
            handler.removeCallbacks(rebindTask);
            rebindPosted = false;
            scheduledDelayMs = 0L;
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
