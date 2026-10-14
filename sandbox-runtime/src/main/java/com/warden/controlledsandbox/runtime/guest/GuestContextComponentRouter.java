package com.warden.controlledsandbox.runtime.guest;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.domain.component.provider.UriGrantRegistry;
import java.util.IdentityHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Standard Context component APIs backed by the virtual runtime Broker. */
final class GuestContextComponentRouter {
    private final GuestContext context;
    private final GuestPackageSpec spec;
    private final GuestRuntimeBrokerBridge bridge;
    private final GuestIntentResolver resolver;
    private final GuestDynamicReceiverRegistry receivers;
    /**
     * Android keeps ordered broadcasts in submission order.  A framework-routed explicit
     * broadcast cannot use the normal synchronous component call from the Guest main thread,
     * because the target ActivityThread receiver callback must be allowed to re-enter that same
     * thread.  Keep the broker calls off the main thread, but serialize them per Guest Context so
     * two back-to-back sendOrderedBroadcast calls cannot overtake one another.
     */
    private final ExecutorService frameworkOrderedBroadcasts;
    private final IdentityHashMap<ServiceConnection, ConnectionRecord> connections = new IdentityHashMap<>();
    private final IdentityHashMap<ServiceConnection, HostConnectionRecord> hostConnections =
            new IdentityHashMap<>();
    private final IdentityHashMap<ServiceConnection, Boolean> failedConnections =
            new IdentityHashMap<>();
    private final IdentityHashMap<ServiceConnection, Boolean> failedHostConnections =
            new IdentityHashMap<>();
    /**
     * Only protects the local connection state.  Never hold this lock while calling AMS, the
     * host Context, or the Broker: those calls can synchronously re-enter ActivityThread and
     * otherwise block unrelated Guest UI work behind a service bind.
     */
    private final Object connectionLock = new Object();
    private final IdentityHashMap<ServiceConnection, Boolean> pendingConnections =
            new IdentityHashMap<>();
    private volatile boolean closed;

    GuestContextComponentRouter(GuestContext context, GuestPackageSpec spec,
            android.content.pm.PackageManager packageManager,
            GuestDynamicReceiverRegistry receivers, GuestMainThreadDispatcher mainThread) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        this.bridge = new GuestRuntimeBrokerBridge(spec, mainThread);
        this.resolver = new GuestIntentResolver(spec, packageManager,
                context.hostPackageManagerService());
        this.receivers = java.util.Objects.requireNonNull(receivers, "receivers");
        this.frameworkOrderedBroadcasts = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable,
                    "guest-framework-ordered-" + spec.virtualUserId + "-" + spec.packageName);
            thread.setDaemon(true);
            return thread;
        });
    }

    void startActivity(Intent intent, Bundle options) {
        startActivity(intent, options, 0);
    }

    void startActivity(Intent intent, Bundle options, int callerTaskId) {
        startActivityInternal(intent, options, callerTaskId, false);
    }

    Bundle startActivityFromFrameworkActivity(Intent intent, Bundle options, int callerTaskId) {
        return startActivityFromFrameworkActivity(intent, options, callerTaskId, -1);
    }

    Bundle startActivityFromFrameworkActivity(Intent intent, Bundle options, int callerTaskId,
                                              int requestCode) {
        return startActivityInternal(intent, options, callerTaskId, requestCode, true);
    }

    void startActivityFromFrameworkActivityAsync(Intent intent, Bundle options, int callerTaskId,
            int requestCode, Consumer<Bundle> success, Consumer<Throwable> failure) {
        // The Instrumentation caller may finish its physical Stub Activity as soon as this
        // method returns. Do not let the delayed Broker transaction validate that now-stale
        // virtual task id; the framework-host transaction can select the current front task and
        // still links its lifecycle observation by session.
        Bundle request = buildActivityRequest(intent, options, callerTaskId, requestCode, true,
                false);
        // The Instrumentation path has already selected the Guest Activity. The actual
        // delegate call is posted back to the framework main thread after the Broker returns,
        // so this thread can continue servicing nativePollOnce and input ACKs meanwhile.
        request.putBoolean(RuntimeKeys.ACTIVITY_FRAMEWORK_HOST, true);
        bridge.launchActivityAsync(request, success, failure);
    }

    private Bundle startActivityInternal(Intent intent, Bundle options, int callerTaskId,
            boolean frameworkHost) {
        return startActivityInternal(intent, options, callerTaskId, -1, frameworkHost);
    }

    private Bundle startActivityInternal(Intent intent, Bundle options, int callerTaskId,
            int requestCode, boolean frameworkHost) {
        Bundle request = buildActivityRequest(intent, options, callerTaskId, requestCode,
                frameworkHost, true);
        if (frameworkHost) {
            // Instrumentation.execStartActivity needs the Broker result before it delegates to
            // the real framework ActivityManager.  Keep that boundary synchronous.
            return bridge.launchActivityFromFrameworkHost(request);
        }
        if (context.mainThread.isMainThread()) {
            // Context.startActivity() has no result to return. Waiting here would put the Guest
            // Looper into GuestMainThreadDispatcher.callBroker()'s re-entrancy spin while a cold
            // target process is being started, starving nativePollOnce and input ACKs.
            bridge.launchActivityAsync(request, ignored -> { }, error ->
                    android.util.Log.e("CS_GUEST_ACTIVITY",
                            "async startActivity failed component="
                                    + request.getString(RuntimeKeys.COMPONENT_CLASS, ""), error));
            return null;
        }
        bridge.launchActivity(request);
        return null;
    }

    private Bundle buildActivityRequest(Intent intent, Bundle options, int callerTaskId,
            int requestCode, boolean frameworkHost, boolean includeCallerTaskId) {
        GuestIntentResolver.Target target = resolver.resolveOne(intent, GuestIntentResolver.Kind.ACTIVITY);
        Bundle request = bridge.baseRequest();
        request.putAll(resolver.activityRequest(intent, target));
        // A virtual task is owned by the package whose Activity is being launched.  A
        // cross-package exported Activity therefore starts in the target package's task
        // namespace; passing the caller's task id would make ActivityTaskLedger reject a
        // legitimate Android-style cross-package launch as a task-owner violation.  Same-package
        // launches retain the caller task for launchMode, result and back-stack semantics.
        if (includeCallerTaskId && callerTaskId > 0
                && spec.packageName.equals(target.packageName())) {
            request.putInt(RuntimeKeys.CALLER_TASK_ID, callerTaskId);
        }
        // A real Activity.startActivityForResult call has already crossed the framework
        // Instrumentation boundary. Preserve its request code in the virtual launch ledger so
        // the child Activity receives the same result ownership the host ActivityManager uses.
        if (frameworkHost && requestCode >= 0) {
            request.putInt(RuntimeKeys.REQUEST_CODE, requestCode);
        }
        if (options != null) request.putBundle("activityOptions", new Bundle(options));
        android.util.Log.i("CS_GUEST_ACTIVITY", "REQUEST frameworkHost=" + frameworkHost
                + " package=" + spec.packageName
                + " session=" + spec.sessionId
                + " generation=" + spec.generation
                + " callerTaskId=" + callerTaskId
                + " requestCode=" + requestCode
                + " targetPackage=" + target.packageName()
                + " targetComponent=" + target.className()
                + " requestId=" + request.getString(RuntimeKeys.REQUEST_ID, "")
                + " operationId=" + request.getString(RuntimeKeys.OPERATION_ID, "")
                + " pid=" + android.os.Process.myPid());
        return request;
    }

    ComponentName startService(Intent intent, boolean foreground) {
        GuestIntentResolver.Target target = resolver.resolveOptionalService(intent);
        if (target == null) {
            logServiceAbsence("startService", intent);
            return null;
        }
        if (target.hostOwned()) {
            return foreground
                    ? context.hostServiceContext().startForegroundService(intent)
                    : context.hostServiceContext().startService(intent);
        }
        Bundle request = bridge.baseRequest();
        request.putAll(resolver.request(intent, target));
        GuestActivityThreadServiceBridge framework = context.serviceFrameworkBridge();
        if (framework != null) return framework.start(request, target.className(), foreground);
        request.putString(ComponentOperations.OPERATION, foreground
                ? ComponentOperations.START_FOREGROUND_SERVICE : ComponentOperations.START_SERVICE);
        bridge.invokeComponent(request);
        return new ComponentName(target.packageName(), target.className());
    }

    boolean stopService(Intent intent) {
        GuestIntentResolver.Target target = resolver.resolveOptionalService(intent);
        if (target == null) {
            logServiceAbsence("stopService", intent);
            return false;
        }
        if (target.hostOwned()) return context.hostServiceContext().stopService(intent);
        Bundle request = bridge.baseRequest();
        request.putAll(resolver.request(intent, target));
        GuestActivityThreadServiceBridge framework = context.serviceFrameworkBridge();
        if (framework != null) return framework.stop(request, target.className());
        request.putString(ComponentOperations.OPERATION, ComponentOperations.STOP_SERVICE);
        Bundle result = bridge.invokeComponent(request);
        return !"SERVICE_NOT_RUNNING".equals(result.getString(RuntimeKeys.STATUS, ""));
    }

    boolean bindService(Intent intent, ServiceConnection connection, int flags, Executor executor) {
        // NBB/VA route ordinary bindService and bindIsolatedService through the same virtual
        // service path. Mark both Guest-owned entry points so a manifest service whose process
        // name is historically isolated cannot fall through to the platform isolated worker.
        return bindServiceInternal(intent, connection, flags, executor, true);
    }

    /**
     * NBB/VA compatibility path for Context.bindIsolatedService: after clearing the instance
     * name, the virtual AMS treats the target as an ordinary virtual Service. The Broker marker
     * is consumed only by the isolated-route policy and does not mint an Android isolated UID.
     */
    boolean bindIsolatedService(Intent intent, ServiceConnection connection, int flags,
            Executor executor) {
        return bindServiceInternal(intent, connection, flags, executor, true);
    }

    private boolean bindServiceInternal(Intent intent, ServiceConnection connection, int flags,
            Executor executor, boolean isolatedServiceFallback) {
        if (connection == null) throw new IllegalArgumentException("connection is required");
        synchronized (connectionLock) {
            if (closed) throw new IllegalStateException("GUEST_COMPONENT_ROUTER_CLOSED");
            if (connections.containsKey(connection) || hostConnections.containsKey(connection)
                    || pendingConnections.containsKey(connection)) {
                throw new IllegalArgumentException("ServiceConnection already bound");
            }
            failedConnections.remove(connection);
            failedHostConnections.remove(connection);
            pendingConnections.put(connection, Boolean.TRUE);
        }
        try {
            GuestIntentResolver.Target target = resolver.resolveOptionalService(intent);
            if (target == null) {
                logServiceAbsence("bindService", intent);
                return false;
            }
            if (target.className().contains("SandboxedPrivilegedProcessService")
                    || target.className().contains("SandboxedProcessService")) {
                android.util.Log.i("CS_RENDERER_BIND", "resolved package=" + target.packageName()
                        + " component=" + target.className() + " process=" + target.processName()
                        + " hostOwned=" + target.hostOwned()
                        + " isolatedFallback=" + isolatedServiceFallback);
            }
            // Chromium's isolated renderer uses android:isolatedProcess=true. The Broker's
            // virtual-service path creates a stub in the CAS host process, which does not mint
            // a real Android isolated UID. Route isolated-renderer binds for the guest's own
            // package directly through the host context so Android starts the real isolated
            // process. The relay maps the component name back to the guest identity so
            // Chromium's ServiceConnection receives the expected ComponentName.
            if (isolatedServiceFallback && !target.hostOwned()
                    && isIsolatedRendererService(target.className())) {
                return bindGuestIsolatedRenderer(intent, connection, flags, executor, target);
            }
            if (target.hostOwned()) {
                Executor callbackExecutor = executor == null ? context.getMainExecutor() : executor;
                ComponentName component = new ComponentName(target.packageName(), target.className());
                GuestServiceConnectionRelay relay = new GuestServiceConnectionRelay(
                        component, connection, callbackExecutor);
                try {
                    // This is deliberately outside connectionLock. Host AMS may wait for a
                    // callback or re-enter Guest code before returning from bindService().
                    boolean accepted = executor == null
                            ? context.hostServiceContext().bindService(intent, relay, flags)
                            : context.hostServiceContext().bindService(intent, flags,
                                    callbackExecutor, relay);
                    if (!accepted) {
                        relay.close();
                        synchronized (connectionLock) {
                            failedHostConnections.put(connection, Boolean.TRUE);
                        }
                        return false;
                    }
                    boolean retired;
                    synchronized (connectionLock) {
                        retired = closed;
                        if (!retired) {
                            hostConnections.put(connection,
                                    new HostConnectionRecord(relay, component));
                        }
                    }
                    if (retired) {
                        relay.close();
                        try { context.hostServiceContext().unbindService(relay); }
                        catch (RuntimeException ignored) { }
                        return false;
                    }
                    android.util.Log.i("CS_GUEST_SERVICE_ROUTE",
                            "owner=HOST_SYSTEM bind=true package=" + target.packageName()
                                    + " component=" + target.className()
                                    + " process=" + target.processName());
                    return true;
                } catch (Throwable error) {
                    relay.close();
                    synchronized (connectionLock) {
                        failedHostConnections.put(connection, Boolean.TRUE);
                    }
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                            .rethrowIfFatal(error);
                    if (error instanceof RuntimeException runtime) throw runtime;
                    if (error instanceof Error fatal) throw fatal;
                    throw new IllegalStateException("HOST_SERVICE_BIND_FAILED", error);
                }
            }
            String connectionId = java.util.UUID.randomUUID().toString();
            Bundle request = bridge.baseRequest();
            request.putAll(resolver.request(intent, target));
            if (isolatedServiceFallback) {
                request.putBoolean(RuntimeKeys.ISOLATED_SERVICE_BIND_FALLBACK, true);
            }
            GuestActivityThreadServiceBridge framework = context.serviceFrameworkBridge();
            if (framework != null) {
                // The framework bridge owns its own relay map and performs host AMS calls
                // asynchronously with respect to this router's local state.
                boolean accepted = framework.bind(request, target.className(), connection, flags,
                        executor);
                if (!accepted) {
                    android.util.Log.w("CS_GUEST_SERVICE", "framework bind rejected component="
                            + target.className());
                }
                return accepted;
            }
            android.util.Log.i("CS_PREPARE_COMPONENT_FALLBACK", "operation="
                    + request.getString(ComponentOperations.OPERATION, "")
                    + " component=" + target.className()
                    + " package=" + target.packageName()
                    + " process=" + target.processName()
                    + " session=" + spec.sessionId
                    + " generation=" + spec.generation);
            request.putString(ComponentOperations.OPERATION, ComponentOperations.BIND_SERVICE);
            request.putString(RuntimeKeys.CONNECTION_ID, connectionId);
            request.putInt(RuntimeKeys.SERVICE_BIND_FLAGS, flags);
            Bundle result;
            try {
                // Broker invocation is also outside connectionLock: it can cross Binder and
                // synchronously call back into ActivityThread.
                result = bridge.invokeComponent(request);
            } catch (RuntimeException error) {
                synchronized (connectionLock) {
                    failedConnections.put(connection, Boolean.TRUE);
                }
                throw error;
            }
            android.os.IBinder binder = result.getBinder(RuntimeKeys.BINDER);
            ComponentName component = new ComponentName(target.packageName(), target.className());
            ConnectionRecord record = new ConnectionRecord(connectionId, target, component,
                    isolatedServiceFallback);
            boolean retired;
            synchronized (connectionLock) {
                retired = closed;
                if (!retired) connections.put(connection, record);
            }
            if (retired) {
                try { unbindGuestRecord(record); }
                catch (RuntimeException error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                            .rethrowIfFatal(error);
                    android.util.Log.w("CS_GUEST_SERVICE",
                            "late bind cleanup failed after router teardown", error);
                }
                return false;
            }
            Executor callbackExecutor = executor == null ? context.getMainExecutor() : executor;
            if (binder == null) callbackExecutor.execute(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    connection.onNullBinding(component);
                } else {
                    connection.onServiceDisconnected(component);
                }
            });
            else callbackExecutor.execute(() -> connection.onServiceConnected(component, binder));
            return true;
        } finally {
            synchronized (connectionLock) {
                pendingConnections.remove(connection);
            }
        }
    }

    void unbindService(ServiceConnection connection) {
        // Activity destruction is asynchronous.  WebView and other framework providers can
        // issue a late unbind after the Guest ActivityThread/Service bridge has already retired
        // its HostConnection map.  The teardown fence is intentionally checked before the
        // framework-owned branch as well; otherwise that branch bypasses the idempotent cleanup
        // used by the Broker-backed path and crashes the caller's worker thread.
        HostConnectionRecord hostRecord;
        boolean failedHost;
        synchronized (connectionLock) {
            if (closed) {
                android.util.Log.i("CS_GUEST_SERVICE",
                        "late framework unbind ignored after component-router teardown");
                return;
            }
            hostRecord = hostConnections.remove(connection);
            failedHost = failedHostConnections.remove(connection) != null;
        }
        if (hostRecord != null) {
            hostRecord.relay.close();
            try {
                // Host AMS is intentionally called after releasing connectionLock.
                context.hostServiceContext().unbindService(hostRecord.relay);
            } catch (RuntimeException error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                        .rethrowIfFatal(error);
                throw error;
            }
            return;
        }
        if (failedHost) {
            android.util.Log.i("CS_GUEST_SERVICE",
                    "unbind ignored after failed host bind");
            return;
        }
        GuestActivityThreadServiceBridge framework = context.serviceFrameworkBridge();
        if (framework != null) {
            framework.unbind(connection);
            return;
        }
        ConnectionRecord record;
        boolean failed;
        synchronized (connectionLock) {
            if (closed) {
                android.util.Log.i("CS_GUEST_SERVICE",
                        "late framework unbind ignored after component-router teardown");
                return;
            }
            failed = failedConnections.remove(connection) != null;
            record = connections.remove(connection);
        }
        if (failed) {
            android.util.Log.i("CS_GUEST_SERVICE",
                    "unbind ignored after failed Guest bind");
            return;
        }
        if (record == null) {
            // WebView can deliver a late unbind after the concrete Guest service has already
            // released its Broker-side bindings.  Preserve Android's strict error while the
            // Guest is live, but make process teardown idempotent so that this lifecycle race
            // cannot crash Chromium's service thread.
            if (closed) {
                android.util.Log.i("CS_GUEST_SERVICE",
                        "late unbind ignored after component-router teardown");
                return;
            }
            throw new IllegalArgumentException("ServiceConnection not bound");
        }
        try {
            unbindGuestRecord(record);
        } catch (RuntimeException error) {
            synchronized (connectionLock) {
                if (!closed && !connections.containsKey(connection)
                        && !pendingConnections.containsKey(connection)) {
                    connections.put(connection, record);
                }
            }
            throw error;
        }
    }

    void updateServiceGroup(ServiceConnection connection, int group, int importance) {
        if (connection == null) throw new IllegalArgumentException("connection is null");
        HostConnectionRecord hostRecord;
        boolean failedHost;
        synchronized (connectionLock) {
            hostRecord = hostConnections.get(connection);
            failedHost = failedHostConnections.containsKey(connection);
            if (closed) {
                hostRecord = null;
                failedHost = false;
            }
        }
        if (closed) {
            android.util.Log.i("CS_GUEST_SERVICE",
                    "late updateServiceGroup ignored after component-router teardown");
            return;
        }
        if (hostRecord != null) {
            // The relay is the actual host AMS binding, just as NBB/VA's IServiceConnection
            // delegate is. Do not pass the Guest callback object to host ContextImpl.
            context.hostServiceContext().updateServiceGroup(hostRecord.relay, group, importance);
            return;
        }
        if (failedHost) {
            android.util.Log.i("CS_GUEST_SERVICE",
                    "updateServiceGroup ignored after failed host bind");
            return;
        }
        GuestActivityThreadServiceBridge framework = context.serviceFrameworkBridge();
        if (framework != null && framework.updateServiceGroup(connection, group, importance)) {
            return;
        }
        ConnectionRecord record;
        boolean failed;
        synchronized (connectionLock) {
            failed = failedConnections.containsKey(connection);
            record = connections.get(connection);
        }
        if (failed) {
            android.util.Log.i("CS_GUEST_SERVICE",
                    "updateServiceGroup ignored after failed Guest bind");
            return;
        }
        if (record != null) {
            // Broker-owned services do not have a physical per-connection process group. The
            // group is advisory, so retain the valid call without leaking a Host AMS handle.
            android.util.Log.i("CS_GUEST_SERVICE", "virtual updateServiceGroup component="
                    + record.component.flattenToShortString() + " group=" + group
                    + " importance=" + importance);
            return;
        }
        throw new IllegalArgumentException("ServiceConnection not bound");
    }

    private void unbindGuestRecord(ConnectionRecord record) {
        Bundle request = bridge.baseRequest();
        request.putString(ComponentOperations.OPERATION, ComponentOperations.UNBIND_SERVICE);
        request.putString(RuntimeKeys.COMPONENT_CLASS, record.target.className());
        request.putString(RuntimeKeys.PROCESS_NAME, processName(record.target));
        request.putString(RuntimeKeys.CONNECTION_ID, record.connectionId);
        if (record.isolatedServiceBindFallback) {
            request.putBoolean(RuntimeKeys.ISOLATED_SERVICE_BIND_FALLBACK, true);
        }
        bridge.invokeComponent(request);
    }

    /**
     * Binds the guest app's own isolated renderer service (SandboxedProcessService*) through the
     * host context so Android mints a real isolated process UID. The Broker virtual-service path
     * only creates CAS stubs and does not start Android isolated processes.
     */
    private boolean bindGuestIsolatedRenderer(Intent intent, ServiceConnection connection,
            int flags, Executor executor, GuestIntentResolver.Target target) {
        Executor callbackExecutor = executor == null ? context.getMainExecutor() : executor;
        ComponentName component = new ComponentName(target.packageName(), target.className());
        GuestServiceConnectionRelay relay = new GuestServiceConnectionRelay(
                component, connection, callbackExecutor);
        String instanceName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && intent.getComponent() != null
                ? intent.getComponent().getClassName() : null;
        // Pre-register so unbind() works even if the host bind hasn't returned yet.
        synchronized (connectionLock) {
            if (closed) {
                relay.close();
                throw new IllegalStateException("GUEST_COMPONENT_ROUTER_CLOSED");
            }
            hostConnections.put(connection, new HostConnectionRecord(relay, component));
        }
        android.util.Log.i("CS_RENDERER_BIND",
                "ISOLATED_HOST_BIND component=" + component.flattenToShortString()
                        + " instance=" + instanceName + " main=" + context.mainThread.isMainThread());
        if (context.mainThread.isMainThread()) {
            final String capturedInstance = instanceName;
            frameworkOrderedBroadcasts.execute(() -> {
                if (closed) {
                    relay.close();
                    synchronized (connectionLock) { hostConnections.remove(connection); }
                    return;
                }
                boolean accepted;
                try {
                    accepted = capturedInstance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? context.hostServiceContext().bindIsolatedService(
                                    intent, flags, capturedInstance, callbackExecutor, relay)
                            : context.hostServiceContext().bindService(intent, relay, flags);
                } catch (Throwable error) {
                    relay.close();
                    synchronized (connectionLock) {
                        hostConnections.remove(connection);
                        failedHostConnections.put(connection, Boolean.TRUE);
                    }
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                            .rethrowIfFatal(error);
                    android.util.Log.e("CS_RENDERER_BIND", "async isolated bind failed component="
                            + component.flattenToShortString(), error);
                    return;
                }
                if (!accepted) {
                    relay.close();
                    synchronized (connectionLock) {
                        hostConnections.remove(connection);
                        failedHostConnections.put(connection, Boolean.TRUE);
                    }
                    android.util.Log.w("CS_RENDERER_BIND", "async isolated bind rejected component="
                            + component.flattenToShortString());
                    return;
                }
                android.util.Log.i("CS_RENDERER_BIND", "ISOLATED_BOUND_ASYNC component="
                        + component.flattenToShortString());
                if (closed) {
                    relay.close();
                    synchronized (connectionLock) { hostConnections.remove(connection); }
                    try { context.hostServiceContext().unbindService(relay); }
                    catch (RuntimeException ignored) { }
                }
            });
            return true;
        }
        boolean accepted;
        try {
            accepted = instanceName != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? context.hostServiceContext().bindIsolatedService(
                            intent, flags, instanceName, callbackExecutor, relay)
                    : context.hostServiceContext().bindService(intent, relay, flags);
        } catch (Throwable error) {
            relay.close();
            synchronized (connectionLock) {
                hostConnections.remove(connection);
                failedHostConnections.put(connection, Boolean.TRUE);
            }
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof RuntimeException runtime) throw runtime;
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException("ISOLATED_RENDERER_BIND_FAILED", error);
        }
        if (!accepted) {
            relay.close();
            synchronized (connectionLock) {
                hostConnections.remove(connection);
                failedHostConnections.put(connection, Boolean.TRUE);
            }
            return false;
        }
        if (closed) {
            relay.close();
            synchronized (connectionLock) { hostConnections.remove(connection); }
            try { context.hostServiceContext().unbindService(relay); }
            catch (RuntimeException ignored) { }
            return false;
        }
        android.util.Log.i("CS_RENDERER_BIND", "ISOLATED_BOUND component="
                + component.flattenToShortString());
        return true;
    }

    private static boolean isIsolatedRendererService(String className) {
        if (className == null) return false;
        final String base = "SandboxedProcessService";
        final String privileged = "SandboxedPrivilegedProcessService";
        return className.contains(base) || className.contains(privileged);
    }

    /**
     * Publishes the teardown fence before ActivityThread starts asynchronous Activity destruction.
     * Android may deliver Activity.onDestroy after finishAndRemoveTask() returns; no Guest
     * component callback from that late lifecycle window may re-enter the Broker or request a
     * replacement generation.
     */
    void beginTeardown() {
        java.util.ArrayList<HostConnectionRecord> boundHostConnections;
        synchronized (connectionLock) {
            if (closed) return;
            closed = true;
            frameworkOrderedBroadcasts.shutdownNow();
            boundHostConnections = new java.util.ArrayList<>(hostConnections.values());
            hostConnections.clear();
            connections.clear();
            failedConnections.clear();
            failedHostConnections.clear();
        }
        for (HostConnectionRecord record : boundHostConnections) {
            record.relay.close();
            try {
                context.hostServiceContext().unbindService(record.relay);
            } catch (RuntimeException error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                        .rethrowIfFatal(error);
            }
        }
        receivers.clear();
    }

    void close() {
        beginTeardown();
    }

    Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String permission, Handler scheduler, int flags) {
        if (receiver == null) return null;
        if (filter == null) {
            throw new IllegalArgumentException("IntentFilter is required");
        }
        if ((flags & Context.RECEIVER_EXPORTED) != 0
                && (flags & Context.RECEIVER_NOT_EXPORTED) != 0) {
            throw new IllegalArgumentException("Receiver cannot be both exported and not exported");
        }
        String receiverId = receivers.reserve(receiver, scheduler);
        boolean asynchronous = context.mainThread.isMainThread();
        Bundle request = null;
        try {
            request = bridge.baseRequest();
            request.putString(ComponentOperations.OPERATION, ComponentOperations.REGISTER_RECEIVER);
            request.putString(RuntimeKeys.COMPONENT_CLASS, receiver.getClass().getName());
            request.putString(RuntimeKeys.RECEIVER_ID, receiverId);
            request.putBoolean(RuntimeKeys.RECEIVER_DYNAMIC_INSTANCE, true);
            GuestIntentFilterWireCodec.encode(request, filter);
            request.putString(RuntimeKeys.RECEIVER_PERMISSION, permission == null ? "" : permission);
            request.putBoolean(RuntimeKeys.RECEIVER_EXPORTED,
                    (flags & Context.RECEIVER_EXPORTED) != 0);
            if (asynchronous) {
                Bundle rollbackRequest = new Bundle(request);
                bridge.invokeComponentAsync(request,
                        ignored -> {
                            if (closed) {
                                throw new IllegalStateException("GUEST_COMPONENT_ROUTER_CLOSED");
                            }
                            GuestActivityThreadServiceBridge framework =
                                    context.serviceFrameworkBridge();
                            if (framework != null) {
                                framework.registerDynamicReceiver(receiverId, receiver, filter,
                                        permission, scheduler, flags);
                            }
                        },
                        error -> rollbackReceiverRegistration(receiverId, rollbackRequest, error));
                // Android's return value is the last matching sticky Intent.  Quark does not use
                // it, and waiting for it here would put this main-thread callback back into the
                // Broker re-entrancy spin loop that starves nativePollOnce/input dispatch.
                return null;
            }
            bridge.invokeComponent(request);
            GuestActivityThreadServiceBridge framework = context.serviceFrameworkBridge();
            return framework == null ? null : framework.registerDynamicReceiver(receiverId,
                    receiver, filter, permission, scheduler, flags);
        } catch (RuntimeException error) {
            if (asynchronous) {
                // Submission failure happens before the asynchronous transaction is accepted;
                // never fall back to a synchronous Broker cleanup on the Guest main thread.
                receivers.rollback(receiverId);
                throw error;
            }
            if (request != null) {
                try {
                    request.putString(ComponentOperations.OPERATION,
                            ComponentOperations.UNREGISTER_RECEIVER);
                    bridge.invokeComponent(request);
                } catch (RuntimeException ignored) { }
            }
            receivers.rollback(receiverId);
            throw error;
        }
    }

    private void rollbackReceiverRegistration(String receiverId, Bundle registeredRequest,
                                              Throwable error) {
        receivers.rollback(receiverId);
        android.util.Log.w("CS_GUEST_RECEIVER",
                "async dynamic receiver registration failed id=" + receiverId, error);
        if (registeredRequest == null) return;
        Bundle cleanup = new Bundle(registeredRequest);
        cleanup.putString(ComponentOperations.OPERATION, ComponentOperations.UNREGISTER_RECEIVER);
        try {
            bridge.invokeComponentAsync(cleanup, ignored -> { }, cleanupError ->
                    android.util.Log.w("CS_GUEST_RECEIVER",
                            "async dynamic receiver rollback failed id=" + receiverId,
                            cleanupError));
        } catch (RuntimeException cleanupSubmissionError) {
            android.util.Log.w("CS_GUEST_RECEIVER",
                    "dynamic receiver rollback submission failed id=" + receiverId,
                    cleanupSubmissionError);
        }
    }

    void unregisterReceiver(BroadcastReceiver receiver) {
        if (closed) {
            // ActivityThread can dispatch onDestroy after the component router has entered its
            // teardown phase.  The registration is already invalidated by the Broker's stop
            // transaction; re-entering the Broker from this late callback would resurrect a
            // stopping generation or block behind the destructive lifecycle barrier.
            android.util.Log.i("CS_GUEST_RECEIVER",
                    "late unregister ignored after component-router teardown");
            return;
        }
        java.util.List<String> receiverIds = receivers.ids(receiver);
        for (String receiverId : receiverIds) {
            GuestActivityThreadServiceBridge framework = context.serviceFrameworkBridge();
            Bundle request = bridge.baseRequest();
            request.putString(ComponentOperations.OPERATION, ComponentOperations.UNREGISTER_RECEIVER);
            request.putString(RuntimeKeys.RECEIVER_ID, receiverId);
            if (context.mainThread.isMainThread()) {
                bridge.invokeComponentAsync(request,
                        ignored -> finishAsyncReceiverUnregistration(receiverId, framework, null),
                        error -> finishAsyncReceiverUnregistration(receiverId, framework, error));
                continue;
            }
            if (framework != null) framework.unregisterDynamicReceiver(receiverId);
            bridge.invokeComponent(request);
            receivers.remove(receiverId);
        }
    }

    private void finishAsyncReceiverUnregistration(String receiverId,
                                                   GuestActivityThreadServiceBridge framework,
                                                   Throwable error) {
        try {
            if (framework != null) framework.unregisterDynamicReceiver(receiverId);
        } catch (RuntimeException cleanupError) {
            android.util.Log.w("CS_GUEST_RECEIVER",
                    "dynamic receiver host unregister failed id=" + receiverId, cleanupError);
        }
        receivers.remove(receiverId);
        if (error != null) {
            android.util.Log.w("CS_GUEST_RECEIVER",
                    "async dynamic receiver unregister failed id=" + receiverId, error);
        }
    }

    void grantUriPermission(String targetPackage, Uri uri, int modeFlags) {
        if (targetPackage == null || targetPackage.trim().isEmpty()) {
            throw new IllegalArgumentException("targetPackage is required");
        }
        if (uri == null) throw new IllegalArgumentException("uri is required");
        bridge.grantUriPermission(targetPackage.trim(), spec.virtualUserId, uri.toString(),
                uriGrantFlags(modeFlags));
    }

    void revokeUriPermission(Uri uri, int modeFlags) {
        if (uri == null) throw new IllegalArgumentException("uri is required");
        bridge.revokeUriPermission(uri.toString(), uriGrantFlags(modeFlags));
    }

    int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        if (uri == null) throw new IllegalArgumentException("uri is required");
        return bridge.checkUriPermission(uri.toString(), pid, uid, uriGrantFlags(modeFlags));
    }

    GuestResourceLoader.LoadedResources openPackageResources(String targetPackage) {
        Bundle result = bridge.openPackageResources(targetPackage);
        ParcelFileDescriptor base = result.getParcelable(RuntimeKeys.PACKAGE_RESOURCE_APK_FD);
        java.util.ArrayList<ParcelFileDescriptor> splits = result.getParcelableArrayList(
                RuntimeKeys.PACKAGE_RESOURCE_SPLIT_FDS);
        if (base == null || base.getFd() < 0) {
            throw new IllegalStateException("PACKAGE_RESOURCE_APK_CAPABILITY_MISSING");
        }
        if (splits == null) splits = new java.util.ArrayList<>();
        try {
            return GuestResourceLoader.load(context.hostServiceContext(), base, splits);
        } catch (Exception error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("PACKAGE_RESOURCE_ASSET_LOAD_FAILED", error);
        } finally {
            try { base.close(); } catch (Throwable ignored) { }
            for (ParcelFileDescriptor split : splits) {
                if (split == null || split == base) continue;
                try { split.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private static int uriGrantFlags(int modeFlags) {
        int result = modeFlags & (UriGrantRegistry.READ | UriGrantRegistry.WRITE);
        // Intent.FLAG_GRANT_PREFIX_URI_PERMISSION is API-stable (0x80), but older static
        // compile stubs used by the RD gate do not expose the symbol.
        if ((modeFlags & 0x80) != 0) {
            result |= UriGrantRegistry.PREFIX;
        }
        if ((result & (UriGrantRegistry.READ | UriGrantRegistry.WRITE)) == 0) {
            throw new IllegalArgumentException("URI grant requires read or write mode");
        }
        return result;
    }

    void sendBroadcast(Intent intent, String receiverPermission) {
        sendBroadcast(intent, receiverPermission, null);
    }

    void sendBroadcast(Intent intent, String receiverPermission, Bundle options) {
        if (intent == null) throw new IllegalArgumentException("intent is required");
        resolver.requireGuestScopeForBroadcast(intent);
        Bundle request = bridge.baseRequest();
        GuestIntentResolver.applyIntent(request, intent);
        // The base request carries the prepared Activity component for launch routing. An
        // implicit broadcast has no component target; leaving that inherited value in place
        // makes the Broker misclassify the request as a manifest broadcast and route it back into
        // GuestComponentRuntime during Activity.onPause().
        if (intent.getComponent() == null) request.remove(RuntimeKeys.COMPONENT_CLASS);
        request.putString(ComponentOperations.OPERATION,
                intent.getComponent() == null
                        ? ComponentOperations.SEND_IMPLICIT_BROADCAST
                        : ComponentOperations.SEND_BROADCAST);
        if (intent.getComponent() != null) {
            GuestIntentResolver.Target target = resolver.resolveOne(
                    intent, GuestIntentResolver.Kind.RECEIVER);
            request.putAll(resolver.request(intent, target));
            // Explicit manifest receivers can use the same ActivityThread RECEIVER transaction
            // as Android. Dynamic registrations remain Broker-owned until a host registration
            // lease is available; never silently mix the two transport contracts.
            request.putBoolean(RuntimeKeys.FRAMEWORK_RECEIVER_ROUTE, true);
        }
        request.putString(RuntimeKeys.BROADCAST_REQUIRED_RECEIVER_PERMISSION,
                receiverPermission == null ? "" : receiverPermission);
        if (options != null) request.putBundle("broadcastOptions", new Bundle(options));

        // Context.sendBroadcast() has no return value.  A synchronous Broker call here is
        // therefore pure waiting on the main thread: while the Broker resolves/starts a target,
        // callBroker's re-entrancy loop keeps this Looper away from nativePollOnce and input
        // dispatch.  Keep synchronous semantics for worker callers, but let the main Looper
        // return immediately.  The Broker operation itself is still serialized by the bridge;
        // only completion/error reporting runs on its worker.
        if (context.mainThread.isMainThread()) {
            bridge.invokeComponentAsync(request,
                    ignored -> { },
                    error -> android.util.Log.w("CS_GUEST_BROADCAST",
                            "async sendBroadcast failed action=" + intent.getAction(), error));
            return;
        }
        bridge.invokeComponent(request);
    }

    void sendOrderedBroadcast(Intent intent, String receiverPermission, Bundle options,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        if (closed) throw new IllegalStateException("GUEST_COMPONENT_ROUTER_CLOSED");
        if (intent == null) throw new IllegalArgumentException("intent is required");
        resolver.requireGuestScopeForBroadcast(intent);
        Bundle request = bridge.baseRequest();
        GuestIntentResolver.applyIntent(request, intent);
        if (intent.getComponent() == null) request.remove(RuntimeKeys.COMPONENT_CLASS);
        request.putString(ComponentOperations.OPERATION, intent.getComponent() == null
                ? ComponentOperations.SEND_ORDERED_BROADCAST
                : ComponentOperations.SEND_BROADCAST);
        boolean frameworkRoute = intent.getComponent() != null;
        if (intent.getComponent() != null) {
            GuestIntentResolver.Target target = resolver.resolveOne(
                    intent, GuestIntentResolver.Kind.RECEIVER);
            request.putAll(resolver.request(intent, target));
            // The Broker issues the ordered token, while the target Guest process completes it
            // from ActivityThread's RECEIVER callback. The outer transaction is asynchronous so
            // the caller's main Handler can dispatch that callback instead of waiting on itself.
            request.putBoolean(RuntimeKeys.FRAMEWORK_RECEIVER_ROUTE, true);
        }
        request.putBoolean(RuntimeKeys.BROADCAST_ORDERED, true);
        request.putString(RuntimeKeys.BROADCAST_REQUIRED_RECEIVER_PERMISSION,
                receiverPermission == null ? "" : receiverPermission);
        request.putInt(RuntimeKeys.BROADCAST_RESULT_CODE, initialCode);
        request.putString(RuntimeKeys.BROADCAST_RESULT_DATA, initialData == null ? "" : initialData);
        request.putBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS,
                initialExtras == null ? new Bundle() : new Bundle(initialExtras));
        if (options != null) request.putBundle("broadcastOptions", new Bundle(options));
        if (frameworkRoute) {
            dispatchFrameworkOrderedBroadcast(request, intent, resultReceiver, scheduler,
                    initialCode, initialData);
            return;
        }
        if (context.mainThread.isMainThread()) {
            // An ordered broadcast has no synchronous return value.  Waiting for the Broker here
            // would put the Guest Looper back into callBroker's re-entrancy spin while the Broker
            // may be waiting for a callback on this same Looper.  Deliver the optional result on
            // its requested scheduler after the worker-side Broker call completes.
            bridge.invokeComponentAsync(request,
                    result -> deliverOrderedResult(intent, resultReceiver, scheduler, initialCode,
                            initialData, result),
                    error -> {
                        android.util.Log.w("CS_GUEST_BROADCAST",
                                "async ordered broadcast failed action=" + intent.getAction(),
                                error);
                        deliverOrderedResult(intent, resultReceiver, scheduler, initialCode,
                                initialData, null);
                    });
            return;
        }
        deliverOrderedResult(intent, resultReceiver, scheduler, initialCode, initialData,
                bridge.invokeComponent(request));
    }

    private void dispatchFrameworkOrderedBroadcast(Bundle request, Intent intent,
                                                   BroadcastReceiver resultReceiver,
                                                   Handler scheduler, int initialCode,
                                                   String initialData) {
        try {
            frameworkOrderedBroadcasts.execute(() -> {
                try {
                    deliverOrderedResult(intent, resultReceiver, scheduler, initialCode,
                            initialData, bridge.invokeComponent(request));
                } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(
                            error);
                    android.util.Log.e("CS_GUEST_BROADCAST",
                            "framework ordered Receiver transport failed", error);
                    deliverOrderedResult(intent, resultReceiver, scheduler, initialCode,
                            initialData, null);
                }
            });
        } catch (RejectedExecutionException error) {
            android.util.Log.e("CS_GUEST_BROADCAST",
                    "framework ordered Receiver transport queue is closed", error);
            deliverOrderedResult(intent, resultReceiver, scheduler, initialCode,
                    initialData, null);
        }
    }

    private void deliverOrderedResult(Intent intent, BroadcastReceiver resultReceiver,
                                      Handler scheduler, int initialCode, String initialData,
                                      Bundle result) {
        if (resultReceiver == null) return;
        Bundle safe = result == null ? new Bundle() : result;
        Runnable delivery = () -> GuestOrderedBroadcastResultDelivery.deliver(context, intent,
                resultReceiver,
                safe.getInt(RuntimeKeys.BROADCAST_RESULT_CODE, initialCode),
                safe.getString(RuntimeKeys.BROADCAST_RESULT_DATA,
                        initialData == null ? "" : initialData),
                safe.getBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS),
                safe.getBoolean(RuntimeKeys.BROADCAST_ABORTED,
                        safe.getBoolean(RuntimeKeys.BROADCAST_ABORT, false)));
        if (scheduler != null) scheduler.post(delivery);
        else context.getMainExecutor().execute(delivery);
    }

    private String processName(GuestIntentResolver.Target target) {
        return target.processName().isEmpty() ? target.packageName() : target.processName();
    }

    private void logServiceAbsence(String api, Intent intent) {
        android.util.Log.i("CS_GUEST_SERVICE_ABSENT", "api=" + api
                + " caller=" + spec.packageName
                + " session=" + spec.sessionId
                + " generation=" + spec.generation
                + " action=" + (intent == null || intent.getAction() == null
                        ? "" : intent.getAction())
                + " component=" + (intent == null || intent.getComponent() == null
                        ? "" : intent.getComponent().flattenToShortString())
                + " package=" + (intent == null || intent.getPackage() == null
                        ? "" : intent.getPackage()));
    }

    private record ConnectionRecord(String connectionId, GuestIntentResolver.Target target,
                                    ComponentName component,
                                    boolean isolatedServiceBindFallback) { }

    private record HostConnectionRecord(GuestServiceConnectionRelay relay,
                                        ComponentName component) { }
}
