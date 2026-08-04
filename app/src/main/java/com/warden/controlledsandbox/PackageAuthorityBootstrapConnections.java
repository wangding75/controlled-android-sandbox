package com.warden.controlledsandbox;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import com.warden.controlledsandbox.contract.IPackageAuthorityBootstrap;
import com.warden.controlledsandbox.contract.RuntimePeerIdentity;
import com.warden.controlledsandbox.runtime.protocol.RuntimePackageAuthorityBootstrapService;

/** Owns Package Service outbound bindings to fixed trusted process endpoints. */
final class PackageAuthorityBootstrapConnections implements AutoCloseable {
    private static final String COMPANION_BOOTSTRAP_CLASS =
            "com.warden.controlledsandbox.runtime.protocol.CompanionRuntimePackageAuthorityBootstrapService";

    private enum RoleKind { MANAGEMENT, HOST_RUNTIME, COMPANION_RUNTIME }

    private final Context context;
    private final PackageAuthorityCapabilityRegistry registry;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RoleConnection management;
    private final RoleConnection runtime;
    private final RoleConnection companionRuntime;
    private boolean closed;

    PackageAuthorityBootstrapConnections(Context context,
            PackageAuthorityCapabilityRegistry registry) {
        this.context = context.getApplicationContext();
        this.registry = registry;
        management = new RoleConnection(
                new Intent(this.context, HostPackageAuthorityBootstrapService.class),
                RoleKind.MANAGEMENT, null);
        runtime = new RoleConnection(
                new Intent(this.context, RuntimePackageAuthorityBootstrapService.class),
                RoleKind.HOST_RUNTIME, null);
        String companionPackage = companionPackageForHost(this.context.getPackageName());
        companionRuntime = new RoleConnection(
                new Intent().setComponent(new ComponentName(
                        companionPackage, COMPANION_BOOTSTRAP_CLASS)),
                RoleKind.COMPANION_RUNTIME, companionPackage);
    }

    void start() {
        management.bind();
        runtime.bind();
        companionRuntime.bind();
    }

    @Override public void close() {
        closed = true;
        management.close();
        runtime.close();
        companionRuntime.close();
    }

    String diagnosticState() {
        return management.diagnosticState() + ";" + runtime.diagnosticState()
                + ";" + companionRuntime.diagnosticState();
    }

    private static String companionPackageForHost(String hostPackage) {
        return RuntimePeerIdentity.HOST_DEBUG_PACKAGE.equals(hostPackage)
                ? RuntimePeerIdentity.COMPANION_DEBUG_PACKAGE
                : RuntimePeerIdentity.COMPANION_RELEASE_PACKAGE;
    }

    private final class RoleConnection implements ServiceConnection {
        private final Intent intent;
        private final RoleKind roleKind;
        private final String companionPackage;
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

        RoleConnection(Intent intent, RoleKind roleKind, String companionPackage) {
            this.intent = intent;
            this.roleKind = roleKind;
            this.companionPackage = companionPackage;
            this.retryBackoff = new PackageAuthorityRetryBackoff(roleKind.hashCode());
        }

        void bind() {
            if (closed || bound) return;
            if (roleKind == RoleKind.COMPANION_RUNTIME && trustedCompanionUid() < 0) {
                scheduleRebind();
                return;
            }
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
                int ownerUid = roleKind == RoleKind.COMPANION_RUNTIME
                        ? trustedCompanionUid() : Process.myUid();
                if (ownerUid < 0 || (companionPackage != null
                        && (name == null || !companionPackage.equals(name.getPackageName())))) {
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
                install(installed, ownerUid, ownerPid);
                retryBackoff.reset();
                scheduledDelayMs = 0L;
            } catch (Exception bootstrapFailure) {
                disconnected();
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) { disconnected(); }
        @Override public void onBindingDied(ComponentName name) { disconnected(); }
        @Override public void onNullBinding(ComponentName name) { disconnected(); }

        private void install(IBinder installed, int ownerUid, int ownerPid) {
            if (roleKind == RoleKind.MANAGEMENT) {
                registry.installManagement(installed, ownerUid, ownerPid);
            } else if (roleKind == RoleKind.HOST_RUNTIME) {
                registry.installRuntime(installed, ownerUid, ownerPid);
            } else {
                registry.installCompanionRuntime(companionPackage, installed, ownerUid, ownerPid);
            }
        }

        private void clear(IBinder stale) {
            if (roleKind == RoleKind.MANAGEMENT) registry.clearManagement(stale);
            else if (roleKind == RoleKind.HOST_RUNTIME) registry.clearRuntime(stale);
            else registry.clearCompanionRuntime(companionPackage, stale);
        }

        private int trustedCompanionUid() {
            if (companionPackage == null || !RuntimePeerIdentity.isCompanionPackage(companionPackage)) {
                return -1;
            }
            PackageManager packages = context.getPackageManager();
            try {
                if (packages.checkSignatures(context.getPackageName(), companionPackage)
                        != PackageManager.SIGNATURE_MATCH) return -1;
                return packages.getPackageUid(companionPackage, 0);
            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                return -1;
            }
        }

        private void disconnected() {
            IBinder stale = capability;
            capability = null;
            if (stale != null) clear(stale);
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
            String role = roleKind == RoleKind.COMPANION_RUNTIME
                    ? "companionRuntime:" + companionPackage
                    : roleKind.name().toLowerCase(java.util.Locale.ROOT);
            return role
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
            if (stale != null) clear(stale);
            unbind();
        }
    }
}
