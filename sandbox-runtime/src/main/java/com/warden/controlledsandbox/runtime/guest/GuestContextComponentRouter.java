package com.warden.controlledsandbox.runtime.guest;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
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
        GuestIntentResolver.Target target = resolver.resolveOne(intent, GuestIntentResolver.Kind.ACTIVITY);
        Bundle request = bridge.baseRequest();
        request.putAll(resolver.request(intent, target));
        if (callerTaskId > 0) request.putInt(RuntimeKeys.CALLER_TASK_ID, callerTaskId);
        if (options != null) request.putBundle("activityOptions", new Bundle(options));
        bridge.launchActivity(request);
    }

    ComponentName startService(Intent intent, boolean foreground) {
        GuestIntentResolver.Target target = resolver.resolveOne(intent, GuestIntentResolver.Kind.SERVICE);
        Bundle request = bridge.baseRequest();
        request.putAll(resolver.request(intent, target));
        request.putString(ComponentOperations.OPERATION, foreground
                ? ComponentOperations.START_FOREGROUND_SERVICE : ComponentOperations.START_SERVICE);
        bridge.invokeComponent(request);
        return new ComponentName(spec.packageName, target.className());
    }

    boolean stopService(Intent intent) {
        GuestIntentResolver.Target target = resolver.resolveOne(intent, GuestIntentResolver.Kind.SERVICE);
        Bundle request = bridge.baseRequest();
        request.putAll(resolver.request(intent, target));
        request.putString(ComponentOperations.OPERATION, ComponentOperations.STOP_SERVICE);
        Bundle result = bridge.invokeComponent(request);
        return !"SERVICE_NOT_RUNNING".equals(result.getString(RuntimeKeys.STATUS, ""));
    }

    synchronized boolean bindService(Intent intent, ServiceConnection connection, int flags,
            Executor executor) {
        if (connection == null) throw new IllegalArgumentException("connection is required");
        if (connections.containsKey(connection)) throw new IllegalArgumentException("ServiceConnection already bound");
        GuestIntentResolver.Target target = resolver.resolveOne(intent, GuestIntentResolver.Kind.SERVICE);
        String connectionId = java.util.UUID.randomUUID().toString();
        Bundle request = bridge.baseRequest();
        request.putAll(resolver.request(intent, target));
        request.putString(ComponentOperations.OPERATION, ComponentOperations.BIND_SERVICE);
        request.putString(RuntimeKeys.CONNECTION_ID, connectionId);
        request.putInt(RuntimeKeys.SERVICE_BIND_FLAGS, flags);
        Bundle result = bridge.invokeComponent(request);
        android.os.IBinder binder = result.getBinder(RuntimeKeys.BINDER);
        ComponentName component = new ComponentName(spec.packageName, target.className());
        ConnectionRecord record = new ConnectionRecord(connectionId, target, component);
        connections.put(connection, record);
        Executor callbackExecutor = executor == null ? context.getMainExecutor() : executor;
        if (binder == null) callbackExecutor.execute(() -> connection.onNullBinding(component));
        else callbackExecutor.execute(() -> connection.onServiceConnected(component, binder));
        return true;
    }

    synchronized void unbindService(ServiceConnection connection) {
        ConnectionRecord record = connections.remove(connection);
        if (record == null) throw new IllegalArgumentException("ServiceConnection not bound");
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
        try {
            Bundle request = bridge.baseRequest();
            request.putString(ComponentOperations.OPERATION, ComponentOperations.REGISTER_RECEIVER);
            request.putString(RuntimeKeys.COMPONENT_CLASS, receiver.getClass().getName());
            request.putString(RuntimeKeys.RECEIVER_ID, receiverId);
            request.putBoolean(RuntimeKeys.RECEIVER_DYNAMIC_INSTANCE, true);
            GuestIntentFilterWireCodec.encode(request, filter);
            request.putString(RuntimeKeys.RECEIVER_PERMISSION, permission == null ? "" : permission);
            request.putBoolean(RuntimeKeys.RECEIVER_EXPORTED,
                    (flags & Context.RECEIVER_EXPORTED) != 0);
            bridge.invokeComponent(request);
            return null;
        } catch (RuntimeException error) {
            receivers.rollback(receiverId);
            throw error;
        }
    }

    void unregisterReceiver(BroadcastReceiver receiver) {
        String receiverId = receivers.id(receiver);
        Bundle request = bridge.baseRequest();
        request.putString(ComponentOperations.OPERATION, ComponentOperations.UNREGISTER_RECEIVER);
        request.putString(RuntimeKeys.RECEIVER_ID, receiverId);
        bridge.invokeComponent(request);
        receivers.remove(receiverId);
    }

    void sendBroadcast(Intent intent, String receiverPermission) {
        sendBroadcast(intent, receiverPermission, null);
    }

    void sendBroadcast(Intent intent, String receiverPermission, Bundle options) {
        if (intent == null) throw new IllegalArgumentException("intent is required");
        resolver.requireGuestScopeForBroadcast(intent);
        Bundle request = bridge.baseRequest();
        GuestIntentResolver.applyIntent(request, intent);
        request.putString(ComponentOperations.OPERATION,
                intent.getComponent() == null
                        ? ComponentOperations.SEND_IMPLICIT_BROADCAST
                        : ComponentOperations.SEND_BROADCAST);
        if (intent.getComponent() != null) {
            GuestIntentResolver.Target target = resolver.resolveOne(
                    intent, GuestIntentResolver.Kind.RECEIVER);
            request.putAll(resolver.request(intent, target));
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
        request.putString(ComponentOperations.OPERATION, intent.getComponent() == null
                ? ComponentOperations.SEND_ORDERED_BROADCAST
                : ComponentOperations.SEND_BROADCAST);
        if (intent.getComponent() != null) {
            GuestIntentResolver.Target target = resolver.resolveOne(
                    intent, GuestIntentResolver.Kind.RECEIVER);
            request.putAll(resolver.request(intent, target));
        }
        request.putBoolean(RuntimeKeys.BROADCAST_ORDERED, true);
        request.putString(RuntimeKeys.BROADCAST_REQUIRED_RECEIVER_PERMISSION,
                receiverPermission == null ? "" : receiverPermission);
        request.putInt(RuntimeKeys.BROADCAST_RESULT_CODE, initialCode);
        request.putString(RuntimeKeys.BROADCAST_RESULT_DATA, initialData == null ? "" : initialData);
        request.putBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS,
                initialExtras == null ? new Bundle() : new Bundle(initialExtras));
        if (options != null) request.putBundle("broadcastOptions", new Bundle(options));
        Bundle result = bridge.invokeComponent(request);
        Runnable delivery = () -> GuestOrderedBroadcastResultDelivery.deliver(context, intent,
                resultReceiver,
                result.getInt(RuntimeKeys.BROADCAST_RESULT_CODE, initialCode),
                result.getString(RuntimeKeys.BROADCAST_RESULT_DATA,
                        initialData == null ? "" : initialData),
                result.getBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS),
                result.getBoolean(RuntimeKeys.BROADCAST_ABORTED,
                        result.getBoolean(RuntimeKeys.BROADCAST_ABORT, false)));
        if (resultReceiver != null) {
            if (scheduler != null) scheduler.post(delivery);
            else context.getMainExecutor().execute(delivery);
        }
    }

    private String processName(GuestIntentResolver.Target target) {
        return target.processName().isEmpty() ? spec.packageName : target.processName();
    }

    private record ConnectionRecord(String connectionId, GuestIntentResolver.Target target,
                                    ComponentName component) { }
}
