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

/** Standard Context component APIs backed by the virtual runtime Broker. */
final class GuestContextComponentRouter {
    private final GuestContext context;
    private final GuestPackageSpec spec;
    private final GuestRuntimeBrokerBridge bridge;
    private final GuestIntentResolver resolver;
    private final GuestDynamicReceiverRegistry receivers;
    private final IdentityHashMap<ServiceConnection, ConnectionRecord> connections = new IdentityHashMap<>();
    private volatile boolean closed;

    GuestContextComponentRouter(GuestContext context, GuestPackageSpec spec,
            android.content.pm.PackageManager packageManager,
            GuestDynamicReceiverRegistry receivers, GuestMainThreadDispatcher mainThread) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        this.bridge = new GuestRuntimeBrokerBridge(spec, mainThread);
        this.resolver = new GuestIntentResolver(spec, packageManager);
        this.receivers = java.util.Objects.requireNonNull(receivers, "receivers");
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

    private Bundle startActivityInternal(Intent intent, Bundle options, int callerTaskId,
            boolean frameworkHost) {
        return startActivityInternal(intent, options, callerTaskId, -1, frameworkHost);
    }

    private Bundle startActivityInternal(Intent intent, Bundle options, int callerTaskId,
            int requestCode, boolean frameworkHost) {
        GuestIntentResolver.Target target = resolver.resolveOne(intent, GuestIntentResolver.Kind.ACTIVITY);
        Bundle request = bridge.baseRequest();
        request.putAll(resolver.activityRequest(intent, target));
        // A virtual task is owned by the package whose Activity is being launched.  A
        // cross-package exported Activity therefore starts in the target package's task
        // namespace; passing the caller's task id would make ActivityTaskLedger reject a
        // legitimate Android-style cross-package launch as a task-owner violation.  Same-package
        // launches retain the caller task for launchMode, result and back-stack semantics.
        if (callerTaskId > 0 && spec.packageName.equals(target.packageName())) {
            request.putInt(RuntimeKeys.CALLER_TASK_ID, callerTaskId);
        }
        // A real Activity.startActivityForResult call has already crossed the framework
        // Instrumentation boundary. Preserve its request code in the virtual launch ledger so
        // the child Activity receives the same result ownership the host ActivityManager uses.
        if (frameworkHost && requestCode >= 0) {
            request.putInt(RuntimeKeys.REQUEST_CODE, requestCode);
        }
        if (options != null) request.putBundle("activityOptions", new Bundle(options));
        Bundle result = frameworkHost
                ? bridge.launchActivityFromFrameworkHost(request)
                : bridge.launchActivity(request);
        return frameworkHost ? result : null;
    }

    ComponentName startService(Intent intent, boolean foreground) {
        GuestIntentResolver.Target target = resolver.resolveOne(intent, GuestIntentResolver.Kind.SERVICE);
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
        GuestIntentResolver.Target target = resolver.resolveOne(intent, GuestIntentResolver.Kind.SERVICE);
        Bundle request = bridge.baseRequest();
        request.putAll(resolver.request(intent, target));
        GuestActivityThreadServiceBridge framework = context.serviceFrameworkBridge();
        if (framework != null) return framework.stop(request, target.className());
        request.putString(ComponentOperations.OPERATION, ComponentOperations.STOP_SERVICE);
        Bundle result = bridge.invokeComponent(request);
        return !"SERVICE_NOT_RUNNING".equals(result.getString(RuntimeKeys.STATUS, ""));
    }

    synchronized boolean bindService(Intent intent, ServiceConnection connection, int flags,
            Executor executor) {
        if (closed) throw new IllegalStateException("GUEST_COMPONENT_ROUTER_CLOSED");
        if (connection == null) throw new IllegalArgumentException("connection is required");
        if (connections.containsKey(connection)) throw new IllegalArgumentException("ServiceConnection already bound");
        GuestIntentResolver.Target target = resolver.resolveOne(intent, GuestIntentResolver.Kind.SERVICE);
        String connectionId = java.util.UUID.randomUUID().toString();
        Bundle request = bridge.baseRequest();
        request.putAll(resolver.request(intent, target));
        GuestActivityThreadServiceBridge framework = context.serviceFrameworkBridge();
        if (framework != null) {
            boolean accepted = framework.bind(request, target.className(), connection, flags, executor);
            if (!accepted) {
                android.util.Log.w("CS_GUEST_SERVICE", "framework bind rejected component="
                        + target.className());
            }
            return accepted;
        }
        request.putString(ComponentOperations.OPERATION, ComponentOperations.BIND_SERVICE);
        request.putString(RuntimeKeys.CONNECTION_ID, connectionId);
        request.putInt(RuntimeKeys.SERVICE_BIND_FLAGS, flags);
        Bundle result = bridge.invokeComponent(request);
        android.os.IBinder binder = result.getBinder(RuntimeKeys.BINDER);
        ComponentName component = new ComponentName(target.packageName(), target.className());
        ConnectionRecord record = new ConnectionRecord(connectionId, target, component);
        connections.put(connection, record);
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
    }

    synchronized void unbindService(ServiceConnection connection) {
        // Activity destruction is asynchronous.  WebView and other framework providers can
        // issue a late unbind after the Guest ActivityThread/Service bridge has already retired
        // its HostConnection map.  The teardown fence is intentionally checked before the
        // framework-owned branch as well; otherwise that branch bypasses the idempotent cleanup
        // used by the Broker-backed path and crashes the caller's worker thread.
        if (closed) {
            android.util.Log.i("CS_GUEST_SERVICE",
                    "late framework unbind ignored after component-router teardown");
            return;
        }
        GuestActivityThreadServiceBridge framework = context.serviceFrameworkBridge();
        if (framework != null) {
            framework.unbind(connection);
            return;
        }
        ConnectionRecord record = connections.remove(connection);
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
        Bundle request = bridge.baseRequest();
        request.putString(ComponentOperations.OPERATION, ComponentOperations.UNBIND_SERVICE);
        request.putString(RuntimeKeys.COMPONENT_CLASS, record.target.className());
        request.putString(RuntimeKeys.PROCESS_NAME, processName(record.target));
        request.putString(RuntimeKeys.CONNECTION_ID, record.connectionId);
        try {
            bridge.invokeComponent(request);
        } catch (RuntimeException error) {
            connections.put(connection, record);
            throw error;
        }
    }

    /**
     * Publishes the teardown fence before ActivityThread starts asynchronous Activity destruction.
     * Android may deliver Activity.onDestroy after finishAndRemoveTask() returns; no Guest
     * component callback from that late lifecycle window may re-enter the Broker or request a
     * replacement generation.
     */
    synchronized void beginTeardown() {
        if (closed) return;
        closed = true;
        connections.clear();
        receivers.clear();
    }

    synchronized void close() {
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
            bridge.invokeComponent(request);
            GuestActivityThreadServiceBridge framework = context.serviceFrameworkBridge();
            return framework == null ? null : framework.registerDynamicReceiver(receiverId,
                    receiver, filter, permission, scheduler, flags);
        } catch (RuntimeException error) {
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
            if (framework != null) framework.unregisterDynamicReceiver(receiverId);
            Bundle request = bridge.baseRequest();
            request.putString(ComponentOperations.OPERATION, ComponentOperations.UNREGISTER_RECEIVER);
            request.putString(RuntimeKeys.RECEIVER_ID, receiverId);
            bridge.invokeComponent(request);
            receivers.remove(receiverId);
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
        bridge.invokeComponent(request);
    }

    void sendOrderedBroadcast(Intent intent, String receiverPermission, Bundle options,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
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
            bridge.invokeComponentAsync(request,
                    result -> deliverOrderedResult(intent, resultReceiver, scheduler, initialCode,
                            initialData, result),
                    error -> {
                        android.util.Log.e("CS_GUEST_BROADCAST",
                                "framework ordered Receiver transport failed", error);
                        deliverOrderedResult(intent, resultReceiver, scheduler, initialCode,
                                initialData, null);
                    });
            return;
        }
        deliverOrderedResult(intent, resultReceiver, scheduler, initialCode, initialData,
                bridge.invokeComponent(request));
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

    private record ConnectionRecord(String connectionId, GuestIntentResolver.Target target,
                                    ComponentName component) { }
}
