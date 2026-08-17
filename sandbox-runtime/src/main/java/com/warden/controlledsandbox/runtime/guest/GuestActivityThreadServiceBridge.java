package com.warden.controlledsandbox.runtime.guest;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;

import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.component.activity.ActivityFieldBridge;
import com.warden.controlledsandbox.runtime.component.service.GuestServiceStubNames;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * ActivityThread-owned Service transport for a Guest process.
 *
 * <p>VA and NBB both let Android deliver the Service messages and retain the framework token;
 * their virtualization point is the client-side ActivityThread callback. CAS follows the same
 * boundary here. A host-declared StubService is only the AMS allocation lease. Once
 * CREATE_SERVICE arrives, the bridge instantiates the Guest class through its AppComponentFactory,
 * attaches the Guest Context/Application, and publishes the Guest object in ActivityThread's
 * {@code mServices}/{@code mServicesData}. Subsequent bind/start/stop messages therefore follow
 * the platform Service lifecycle rather than a Broker-side manual lifecycle.</p>
 */
public final class GuestActivityThreadServiceBridge implements AutoCloseable {
    private final GuestRuntimeEnvironment.Session session;
    private final Object activityThread;
    private final Handler handler;
    private final Field callbackField;
    private final Handler.Callback previousCallback;
    private final Handler.Callback callback;
    private final GuestRuntimeBrokerBridge routeBroker;
    private final GuestActivityThreadServiceLifecycle serviceLifecycle;
    private final GuestActivityThreadReceiverBridge receiverBridge;
    private volatile boolean closed;

    private GuestActivityThreadServiceBridge(GuestRuntimeEnvironment.Session session,
                                              Object activityThread, Handler handler,
                                              Field callbackField, Handler.Callback previousCallback,
                                              Object servicesData, Object services,
                                              Object activityManager) {
        this.session = Objects.requireNonNull(session, "session");
        this.activityThread = activityThread;
        this.handler = handler;
        this.callbackField = callbackField;
        this.previousCallback = previousCallback;
        this.routeBroker = new GuestRuntimeBrokerBridge(session.spec, session.mainThread);
        this.serviceLifecycle = new GuestActivityThreadServiceLifecycle(session, activityThread,
                servicesData, services, activityManager, routeBroker);
        this.receiverBridge = new GuestActivityThreadReceiverBridge(session);
        this.callback = this::handleMessage;
    }

    static GuestActivityThreadServiceBridge install(GuestRuntimeEnvironment.Session session)
            throws Exception {
        Class<?> activityThreadType = Class.forName("android.app.ActivityThread");
        Method currentMethod = activityThreadType.getDeclaredMethod("currentActivityThread");
        currentMethod.setAccessible(true);
        Object activityThread = currentMethod.invoke(null);
        if (activityThread == null) throw new IllegalStateException("GUEST_ACTIVITY_THREAD_UNAVAILABLE");
        Field handlerField = findField(activityThreadType, "mH");
        handlerField.setAccessible(true);
        Handler handler = (Handler) handlerField.get(activityThread);
        if (handler == null) throw new IllegalStateException("GUEST_ACTIVITY_THREAD_HANDLER_UNAVAILABLE");
        Field callbackField = findField(Handler.class, "mCallback");
        callbackField.setAccessible(true);
        Handler.Callback previous = (Handler.Callback) callbackField.get(handler);
        Field servicesDataField = findField(activityThreadType, "mServicesData");
        servicesDataField.setAccessible(true);
        Field servicesField = findField(activityThreadType, "mServices");
        servicesField.setAccessible(true);
        Object activityManager = GuestActivityThreadServiceLifecycle.activityManager();
        GuestActivityThreadServiceBridge bridge = new GuestActivityThreadServiceBridge(session,
                activityThread, handler, callbackField, previous, servicesDataField.get(activityThread),
                servicesField.get(activityThread), activityManager);
        callbackField.set(handler, bridge.callback);
        android.util.Log.i("CS_SERVICE_FRAMEWORK", "installed process=" + session.spec.processName);
        return bridge;
    }

    /** Starts a Guest Service through AMS/ActivityThread using a predeclared process-slot stub. */
    ComponentName start(Bundle request, String guestClass, boolean foreground) {
        Bundle routedRequest = new Bundle(request == null ? new Bundle() : request);
        routedRequest.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_FOREGROUND, foreground);
        Route route = route(routedRequest, guestClass);
        Intent hostIntent = hostIntent(route);
        ComponentName started = foreground
                ? session.context.hostServiceContext().startForegroundService(hostIntent)
                : session.context.hostServiceContext().startService(hostIntent);
        if (started == null) throw new IllegalStateException("FRAMEWORK_SERVICE_START_RETURNED_NULL");
        return new ComponentName(session.spec.packageName, guestClass);
    }

    /**
     * Restarts a sticky/redeliver Service through the platform Service queue and waits until the
     * real ActivityThread SERVICE_ARGS callback has completed. The wait is performed off the
     * Guest main thread so the callback can continue through the Handler normally.
     */
    Bundle recover(Bundle request, String guestClass, boolean foreground) {
        if (closed) throw new IllegalStateException("GUEST_SERVICE_FRAMEWORK_BRIDGE_CLOSED");
        Bundle routedRequest = new Bundle(request == null ? new Bundle() : request);
        routedRequest.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_FOREGROUND, foreground);
        return serviceLifecycle.recover(routedRequest, guestClass, () -> {
                Route route = route(routedRequest, guestClass);
                Intent hostIntent = hostIntent(route);
                ComponentName started = foreground
                        ? session.context.hostServiceContext().startForegroundService(hostIntent)
                        : session.context.hostServiceContext().startService(hostIntent);
                if (started == null) throw new IllegalStateException("FRAMEWORK_SERVICE_RECOVERY_START_NULL");
                return started;
        });
    }

    boolean stop(Bundle request, String guestClass) {
        Route route = route(request, guestClass);
        return session.context.hostServiceContext().stopService(hostIntent(route));
    }

    boolean bind(Bundle request, String guestClass, ServiceConnection guestConnection,
                 int flags, Executor executor) {
        if (closed) throw new IllegalStateException("GUEST_SERVICE_FRAMEWORK_BRIDGE_CLOSED");
        Bundle routedRequest = new Bundle(request == null ? new Bundle() : request);
        if (!routedRequest.containsKey(RuntimeKeys.CONNECTION_ID)) {
            routedRequest.putString(RuntimeKeys.CONNECTION_ID, UUID.randomUUID().toString());
        }
        routedRequest.putBinder(RuntimeKeys.SERVICE_CONNECTION_BINDER, new Binder());
        Route route = route(routedRequest, guestClass);
        HostConnection hostConnection = new HostConnection(route, guestConnection,
                executor == null ? session.context.getMainExecutor() : executor);
        if (closed) {
            hostConnection.close();
            synchronized (hostConnections) { hostConnections.remove(guestConnection, hostConnection); }
            throw new IllegalStateException("GUEST_SERVICE_FRAMEWORK_BRIDGE_CLOSED");
        }
        boolean accepted;
        try {
            accepted = session.context.hostServiceContext().bindService(hostIntent(route),
                    hostConnection, flags);
        } catch (Throwable error) {
            hostConnection.close();
            synchronized (hostConnections) { hostConnections.remove(guestConnection, hostConnection); }
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw error instanceof RuntimeException
                    ? (RuntimeException) error : new IllegalStateException(error);
        }
        if (accepted) {
            boolean retired;
            synchronized (hostConnections) {
                retired = closed || hostConnections.get(guestConnection) != hostConnection;
                if (!retired) hostConnection.published = true;
                else hostConnections.remove(guestConnection, hostConnection);
            }
            if (retired) {
                hostConnection.close();
                try { session.context.hostServiceContext().unbindService(hostConnection); }
                catch (RuntimeException ignored) { }
                return false;
            }
        } else {
            hostConnection.close();
            hostConnection.published = false;
            synchronized (hostConnections) { hostConnections.remove(guestConnection, hostConnection); }
        }
        return accepted;
    }

    Bundle dispatchFrameworkReceiver(Bundle request, String guestClass) {
        return receiverBridge.dispatchFrameworkReceiver(request, guestClass);
    }

    Intent registerDynamicReceiver(String receiverId, BroadcastReceiver guestReceiver,
                                   IntentFilter filter, String permission, Handler scheduler,
                                   int flags) {
        return receiverBridge.registerDynamicReceiver(receiverId, guestReceiver, filter,
                permission, scheduler, flags);
    }

    void unregisterDynamicReceiver(String receiverId) {
        receiverBridge.unregisterDynamicReceiver(receiverId);
    }

    void unbind(ServiceConnection guestConnection) {
        if (closed) {
            android.util.Log.i("CS_SERVICE_FRAMEWORK",
                    "late unbind ignored after framework bridge teardown");
            return;
        }
        HostConnection found = null;
        synchronized (hostConnections) {
            found = hostConnections.remove(guestConnection);
        }
        if (found == null) {
            // close() may win the race after the first closed check and clear the map while a
            // framework callback is still unwinding.  Keep strict Android behavior while live,
            // but converge teardown races on an idempotent terminal edge.
            if (closed) {
                android.util.Log.i("CS_SERVICE_FRAMEWORK",
                        "late unbind ignored after framework bridge teardown");
                return;
            }
            throw new IllegalArgumentException("ServiceConnection not bound");
        }
        // Fence the Guest callback queue before asking host AMS to remove the binding.  AMS is
        // allowed to race a pending onServiceConnected/onBindingDied notification with unbind.
        found.close();
        session.context.hostServiceContext().unbindService(found);
    }

    private final Map<ServiceConnection, HostConnection> hostConnections = new IdentityHashMap<>();

    private Route route(Bundle request, String guestClass) {
        if (closed) throw new IllegalStateException("GUEST_SERVICE_FRAMEWORK_BRIDGE_CLOSED");
        Bundle routedRequest = new Bundle(request == null ? new Bundle() : request);
        routedRequest.putString(ComponentOperations.OPERATION,
                ComponentOperations.ROUTE_FRAMEWORK_SERVICE);
        routedRequest.putString(RuntimeKeys.COMPONENT_CLASS, guestClass);
        Bundle routed = routeBroker.invokeComponent(routedRequest);
        if (!"FRAMEWORK_SERVICE_ROUTE".equals(routed.getString(RuntimeKeys.STATUS, ""))) {
            throw new IllegalStateException("FRAMEWORK_SERVICE_ROUTE_REJECTED:" +
                    routed.getString(RuntimeKeys.STATUS, ""));
        }
        return new Route(routed.getString(RuntimeKeys.SESSION_ID),
                routed.getLong(RuntimeKeys.GENERATION), routed.getInt(RuntimeKeys.PROCESS_SLOT, -1),
                routed.getString(RuntimeKeys.PACKAGE_NAME),
                routed.getString("frameworkServiceStubPackage",
                        session.context.hostServiceContext().getPackageName()),
                guestClass, new Bundle(request));
    }

    private Intent hostIntent(Route route) {
        String stub = GuestServiceStubNames.classNameFor(route.slot);
        Intent intent = new Intent().setComponent(new ComponentName(
                route.stubPackage, stub));
        intent.putExtra("frameworkServiceRoute", true);
        intent.putExtra(RuntimeKeys.SESSION_ID, route.sessionId);
        intent.putExtra(RuntimeKeys.GENERATION, route.generation);
        intent.putExtra(RuntimeKeys.PROCESS_SLOT, route.slot);
        intent.putExtra(RuntimeKeys.PACKAGE_NAME, route.packageName);
        intent.putExtra(RuntimeKeys.COMPONENT_CLASS, route.guestClass);
        copyString(route.request, intent, RuntimeKeys.PROCESS_NAME);
        copyString(route.request, intent, ComponentOperations.ACTION);
        copyString(route.request, intent, RuntimeKeys.URI);
        copyString(route.request, intent, RuntimeKeys.BROADCAST_SCHEME);
        copyString(route.request, intent, RuntimeKeys.BROADCAST_HOST);
        copyInt(route.request, intent, RuntimeKeys.BROADCAST_PORT);
        copyString(route.request, intent, RuntimeKeys.BROADCAST_PATH);
        copyString(route.request, intent, RuntimeKeys.BROADCAST_MIME_TYPE);
        copyString(route.request, intent, RuntimeKeys.INTENT_COMPONENT_PACKAGE);
        copyString(route.request, intent, RuntimeKeys.INTENT_COMPONENT_CLASS);
        copyString(route.request, intent, RuntimeKeys.CONNECTION_ID);
        copyBoolean(route.request, intent, RuntimeKeys.SERVICE_RECOVERY);
        copyBoolean(route.request, intent, RuntimeKeys.SERVICE_REDELIVERED);
        copyBoolean(route.request, intent, RuntimeKeys.FRAMEWORK_SERVICE_FOREGROUND);
        copyInt(route.request, intent, RuntimeKeys.SERVICE_START_ID);
        copyInt(route.request, intent, RuntimeKeys.SERVICE_START_RESULT);
        copyLong(route.request, intent, RuntimeKeys.SERVICE_FOREGROUND_PROMOTION_TIMEOUT_MS);
        copyInt(route.request, intent, RuntimeKeys.SERVICE_FOREGROUND_DECLARED_TYPE_MASK);
        copyBoolean(route.request, intent, RuntimeKeys.SERVICE_FOREGROUND_BACKGROUND_ALLOWED);
        copyString(route.request, intent, RuntimeKeys.SERVICE_FOREGROUND_EXEMPTION_REASON);
        copyBinder(route.request, intent, RuntimeKeys.SERVICE_CONNECTION_BINDER);
        copyInt(route.request, intent, RuntimeKeys.ACTIVITY_FLAGS);
        copyStringList(route.request, intent, RuntimeKeys.BROADCAST_CATEGORIES);
        Bundle extras = route.request.getBundle(RuntimeKeys.INTENT_EXTRAS);
        if (extras != null) intent.putExtra(RuntimeKeys.INTENT_EXTRAS, new Bundle(extras));
        return intent;
    }

    private boolean handleMessage(Message message) {
        try {
            if (closed) return delegate(message);
            // ClientTransaction is the last framework-owned boundary before ActivityThread
            // creates ActivityClientRecord and selects the Activity.onCreate overload.  Let the
            // Activity bridge project Guest metadata here; the existing Service callback then
            // continues to own CREATE/BIND/ARGS/STOP and delegates every unrelated message.
            ActivityFieldBridge.projectFrameworkLaunchTransaction(activityThread, message, session);
            if (receiverBridge.handles(message)) {
                receiverBridge.handle(message.obj);
                return true;
            }
            if (serviceLifecycle.handle(message)) {
                return true;
            }
            return delegate(message);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_SERVICE_FRAMEWORK", "dispatch failed", error);
            throw error instanceof RuntimeException
                    ? (RuntimeException) error : new RuntimeException(error);
        }
    }

    private boolean delegate(Message message) {
        return previousCallback != null && previousCallback.handleMessage(message);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        serviceLifecycle.close();
        ArrayList<HostConnection> boundConnections;
        synchronized (hostConnections) {
            boundConnections = new ArrayList<>(hostConnections.values());
            hostConnections.clear();
        }
        for (HostConnection connection : boundConnections) {
            connection.close();
            try { session.context.hostServiceContext().unbindService(connection); }
            catch (RuntimeException ignored) { }
        }
        receiverBridge.close();
        try {
            if (callbackField.get(handler) == callback) callbackField.set(handler, previousCallback);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_SERVICE_FRAMEWORK", "restore callback failed", error);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static void copyString(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) target.putExtra(key, source.getString(key));
    }

    private static void copyBoolean(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) target.putExtra(key, source.getBoolean(key));
    }

    private static void copyInt(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) target.putExtra(key, source.getInt(key));
    }

    private static void copyLong(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) target.putExtra(key, source.getLong(key));
    }

    private static void copyBinder(Bundle source, Intent target, String key) {
        if (source == null || !source.containsKey(key)) return;
        Bundle extras = target.getExtras();
        if (extras == null) {
            extras = new Bundle();
            target.putExtras(extras);
        }
        extras.putBinder(key, source.getBinder(key));
    }

    private static void copyStringList(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) {
            ArrayList<String> values = source.getStringArrayList(key);
            if (values != null) target.putStringArrayListExtra(key, new ArrayList<>(values));
        }
    }

    private record Route(String sessionId, long generation, int slot, String packageName,
                         String stubPackage, String guestClass, Bundle request) { }

    private final class HostConnection implements ServiceConnection {
        final Route route;
        final GuestServiceConnectionRelay relay;
        volatile boolean published;
        volatile boolean closed;
        HostConnection(Route route, ServiceConnection guest, Executor executor) {
            this.route = route;
            this.relay = new GuestServiceConnectionRelay(
                    new ComponentName(route.packageName, route.guestClass), guest, executor);
            synchronized (hostConnections) { hostConnections.put(guest, this); }
        }
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (closed || GuestActivityThreadServiceBridge.this.closed) return;
            android.util.Log.i("CS_SERVICE_FRAMEWORK", "HOST_CONNECTED guest=" + route.guestClass
                    + " binder=" + service);
            relay.onServiceConnected(name, service);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (closed || GuestActivityThreadServiceBridge.this.closed) return;
            android.util.Log.i("CS_SERVICE_FRAMEWORK", "HOST_DISCONNECTED guest=" + route.guestClass);
            relay.onServiceDisconnected(name);
        }
        @Override public void onBindingDied(ComponentName name) {
            if (!closed && !GuestActivityThreadServiceBridge.this.closed
                    && android.os.Build.VERSION.SDK_INT >= 26) relay.onBindingDied(name);
        }
        @Override public void onNullBinding(ComponentName name) {
            if (!closed && !GuestActivityThreadServiceBridge.this.closed
                    && android.os.Build.VERSION.SDK_INT >= 28) {
                android.util.Log.i("CS_SERVICE_FRAMEWORK", "HOST_NULL_BINDING guest=" + route.guestClass);
                relay.onNullBinding(name);
            }
        }
        void close() {
            if (closed) return;
            closed = true;
            relay.close();
        }
    }
}
