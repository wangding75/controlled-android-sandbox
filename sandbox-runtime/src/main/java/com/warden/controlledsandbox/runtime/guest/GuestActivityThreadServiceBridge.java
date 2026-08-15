package com.warden.controlledsandbox.runtime.guest;

import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;

import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.component.service.GuestServiceStubNames;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

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
    private static final int CREATE_SERVICE = 114;
    private static final int SERVICE_ARGS = 115;
    private static final int STOP_SERVICE = 116;
    private static final int BIND_SERVICE = 121;
    private static final int UNBIND_SERVICE = 122;
    private static final int SERVICE_DONE_EXECUTING_ANON = 0;
    private static final int SERVICE_DONE_EXECUTING_START = 1;
    private static final int SERVICE_DONE_EXECUTING_STOP = 2;
    private static final int START_TASK_REMOVED_COMPLETE = 1000;

    private final GuestRuntimeEnvironment.Session session;
    private final Object activityThread;
    private final Handler handler;
    private final Field callbackField;
    private final Handler.Callback previousCallback;
    private final Handler.Callback callback;
    private final Map<IBinder, Record> records = new IdentityHashMap<>();
    private final GuestRuntimeBrokerBridge routeBroker;
    private final Object servicesData;
    private final Object services;
    private final Object activityManager;
    private final Class<?> activityThreadType;
    private volatile boolean closed;

    private GuestActivityThreadServiceBridge(GuestRuntimeEnvironment.Session session,
                                              Object activityThread, Handler handler,
                                              Field callbackField, Handler.Callback previousCallback,
                                              Object servicesData, Object services,
                                              Object activityManager) {
        this.session = Objects.requireNonNull(session, "session");
        this.activityThread = activityThread;
        this.activityThreadType = activityThread.getClass();
        this.handler = handler;
        this.callbackField = callbackField;
        this.previousCallback = previousCallback;
        this.servicesData = servicesData;
        this.services = services;
        this.activityManager = activityManager;
        this.routeBroker = new GuestRuntimeBrokerBridge(session.spec, session.mainThread);
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
        Object activityManager = activityManager();
        GuestActivityThreadServiceBridge bridge = new GuestActivityThreadServiceBridge(session,
                activityThread, handler, callbackField, previous, servicesDataField.get(activityThread),
                servicesField.get(activityThread), activityManager);
        callbackField.set(handler, bridge.callback);
        android.util.Log.i("CS_SERVICE_FRAMEWORK", "installed process=" + session.spec.processName);
        return bridge;
    }

    /** Starts a Guest Service through AMS/ActivityThread using a predeclared process-slot stub. */
    ComponentName start(Bundle request, String guestClass, boolean foreground) {
        Route route = route(request, guestClass);
        Intent hostIntent = hostIntent(route);
        ComponentName started = foreground
                ? session.context.hostServiceContext().startForegroundService(hostIntent)
                : session.context.hostServiceContext().startService(hostIntent);
        if (started == null) throw new IllegalStateException("FRAMEWORK_SERVICE_START_RETURNED_NULL");
        return new ComponentName(session.spec.packageName, guestClass);
    }

    boolean stop(Bundle request, String guestClass) {
        Route route = route(request, guestClass);
        return session.context.hostServiceContext().stopService(hostIntent(route));
    }

    boolean bind(Bundle request, String guestClass, ServiceConnection guestConnection,
                 int flags, Executor executor) {
        Route route = route(request, guestClass);
        HostConnection hostConnection = new HostConnection(route, guestConnection,
                executor == null ? session.context.getMainExecutor() : executor);
        boolean accepted = session.context.hostServiceContext().bindService(hostIntent(route),
                hostConnection, flags);
        if (accepted) hostConnection.published = true;
        else {
            hostConnection.published = false;
            synchronized (hostConnections) { hostConnections.remove(guestConnection); }
        }
        return accepted;
    }

    void unbind(ServiceConnection guestConnection) {
        HostConnection found = null;
        synchronized (hostConnections) {
            found = hostConnections.remove(guestConnection);
        }
        if (found == null) throw new IllegalArgumentException("ServiceConnection not bound");
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
                routed.getString(RuntimeKeys.PACKAGE_NAME), guestClass, new Bundle(request));
    }

    private Intent hostIntent(Route route) {
        String stub = GuestServiceStubNames.classNameFor(route.slot);
        Intent intent = new Intent().setComponent(new ComponentName(
                session.context.hostServiceContext().getPackageName(), stub));
        intent.putExtra("frameworkServiceRoute", true);
        intent.putExtra(RuntimeKeys.SESSION_ID, route.sessionId);
        intent.putExtra(RuntimeKeys.GENERATION, route.generation);
        intent.putExtra(RuntimeKeys.PROCESS_SLOT, route.slot);
        intent.putExtra(RuntimeKeys.PACKAGE_NAME, route.packageName);
        intent.putExtra(RuntimeKeys.COMPONENT_CLASS, route.guestClass);
        copyString(route.request, intent, RuntimeKeys.PROCESS_NAME);
        copyString(route.request, intent, ComponentOperations.ACTION);
        copyString(route.request, intent, RuntimeKeys.URI);
        copyString(route.request, intent, RuntimeKeys.BROADCAST_MIME_TYPE);
        copyString(route.request, intent, RuntimeKeys.INTENT_COMPONENT_PACKAGE);
        copyString(route.request, intent, RuntimeKeys.INTENT_COMPONENT_CLASS);
        copyInt(route.request, intent, RuntimeKeys.ACTIVITY_FLAGS);
        copyStringList(route.request, intent, RuntimeKeys.BROADCAST_CATEGORIES);
        Bundle extras = route.request.getBundle(RuntimeKeys.INTENT_EXTRAS);
        if (extras != null) intent.putExtra(RuntimeKeys.INTENT_EXTRAS, new Bundle(extras));
        return intent;
    }

    private boolean handleMessage(Message message) {
        try {
            if (closed) return delegate(message);
            if (message.what == CREATE_SERVICE && isFrameworkRoute(createIntent(message.obj))) {
                createService(message.obj);
                return true;
            }
            IBinder token = tokenFor(message.what, message.obj);
            Record record;
            synchronized (records) { record = token == null ? null : records.get(token); }
            if (record == null && token != null
                    && (message.what == BIND_SERVICE || message.what == SERVICE_ARGS)) {
                Intent routeIntent = lifecycleIntent(message.what, message.obj);
                if (isFrameworkRoute(routeIntent)) {
                    promoteCreatedService(token, routeIntent);
                    synchronized (records) { record = records.get(token); }
                }
            }
            if (record != null) {
                switch (message.what) {
                    case BIND_SERVICE -> bindService(message.obj, record);
                    case UNBIND_SERVICE -> unbindService(message.obj, record);
                    case SERVICE_ARGS -> serviceArgs(message.obj, record);
                    case STOP_SERVICE -> stopService(token, record);
                    default -> { return delegate(message); }
                }
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

    private void createService(Object data) throws Exception {
        Intent hostIntent = createIntent(data);
        String sessionId = hostIntent.getStringExtra(RuntimeKeys.SESSION_ID);
        long generation = hostIntent.getLongExtra(RuntimeKeys.GENERATION, -1L);
        if (!session.spec.sessionId.equals(sessionId) || session.spec.generation != generation) {
            throw new SecurityException("FRAMEWORK_SERVICE_ROUTE_GENERATION_MISMATCH");
        }
        String guestClass = hostIntent.getStringExtra(RuntimeKeys.COMPONENT_CLASS);
        if (guestClass == null || guestClass.trim().isEmpty()) {
            throw new IllegalArgumentException("FRAMEWORK_SERVICE_COMPONENT_MISSING");
        }
        IBinder token = (IBinder) field(data, "token");
        Intent guestIntent = decodeGuestIntent(hostIntent);
        installGuestService(data, token, hostIntent, false);
    }

    /**
     * API32's CREATE_SERVICE packet has no start Intent. The first BIND_SERVICE/SERVICE_ARGS
     * packet does carry our route, so replace the framework-created Stub object at that token
     * before dispatching the actual Guest callback.
     */
    private void promoteCreatedService(IBinder token, Intent hostIntent) throws Exception {
        Object data = mapGet(servicesData, token);
        if (data == null) throw new IllegalStateException("FRAMEWORK_SERVICE_CREATE_DATA_MISSING");
        installGuestService(data, token, hostIntent, true);
    }

    private void installGuestService(Object data, IBinder token, Intent hostIntent,
                                     boolean replaceExisting) throws Exception {
        String sessionId = hostIntent.getStringExtra(RuntimeKeys.SESSION_ID);
        long generation = hostIntent.getLongExtra(RuntimeKeys.GENERATION, -1L);
        if (!session.spec.sessionId.equals(sessionId) || session.spec.generation != generation) {
            throw new SecurityException("FRAMEWORK_SERVICE_ROUTE_GENERATION_MISMATCH");
        }
        String guestClass = hostIntent.getStringExtra(RuntimeKeys.COMPONENT_CLASS);
        if (guestClass == null || guestClass.trim().isEmpty()) {
            throw new IllegalArgumentException("FRAMEWORK_SERVICE_COMPONENT_MISSING");
        }
        if (replaceExisting) {
            Object old = mapGet(services, token);
            if (old instanceof Service) {
                try { ((Service) old).onDestroy(); } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
                try { detach((Service) old); } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
            }
        }
        Intent guestIntent = decodeGuestIntent(hostIntent);
        Service service = GuestComponentFactory.instantiateService(session.context.getClassLoader(),
                session.context.getApplicationInfo().appComponentFactory, guestClass, guestIntent);
        if (service == null) throw new IllegalStateException("FRAMEWORK_SERVICE_FACTORY_RETURNED_NULL");
        if (!(service instanceof Service)) throw new IllegalArgumentException("NOT_A_GUEST_SERVICE:" + guestClass);
        ServiceInfo projected = projectServiceInfo(data, guestClass);
        setField(data, "info", projected);
        Object manager = serviceManagerProxy(projected);
        Method attach = Service.class.getDeclaredMethod("attach", Context.class,
                activityThreadType, String.class, IBinder.class, Application.class, Object.class);
        attach.setAccessible(true);
        attach.invoke(service, session.context, activityThread, guestClass, token,
                session.application, manager);
        service.onCreate();
        synchronized (records) {
            records.put(token, new Record(token, service, guestClass, projected,
                    new ComponentName(session.context.hostServiceContext(),
                            GuestServiceStubNames.classNameFor(session.spec.processSlot))));
        }
        mapPut(servicesData, token, data);
        mapPut(services, token, service);
        if (!replaceExisting) serviceDone(token, SERVICE_DONE_EXECUTING_ANON, 0, 0);
        Bundle event = new Bundle();
        event.putString(RuntimeKeys.STATUS, "FRAMEWORK_SERVICE_CREATED");
        event.putBoolean("frameworkOwnedService", true);
        event.putString(RuntimeKeys.COMPONENT_CLASS, guestClass);
        event.putString(RuntimeKeys.SESSION_ID, session.spec.sessionId);
        event.putLong(RuntimeKeys.GENERATION, session.spec.generation);
        event.putInt(RuntimeKeys.PROCESS_SLOT, session.spec.processSlot);
        event.putString("token", String.valueOf(token));
        RuntimeEventLog.event("GUEST_SERVICE_FRAMEWORK_CREATED", event);
        android.util.Log.i("CS_SERVICE_FRAMEWORK", (replaceExisting ? "PROMOTE_SERVICE" : "CREATE_SERVICE")
                + " guest=" + guestClass + " process=" + session.spec.processName + " token=" + token);
    }

    private void bindService(Object data, Record record) throws Exception {
        Intent hostIntent = (Intent) field(data, "intent");
        Intent intent = decodeGuestIntent(hostIntent);
        boolean rebind = booleanField(data, "rebind");
        if (rebind) record.service.onRebind(intent);
        else record.lastBinder = record.service.onBind(intent);
        if (rebind) serviceDone(record.token, SERVICE_DONE_EXECUTING_ANON, 0, 0);
        else publishService(record.token, hostIntent, record.lastBinder);
        logLifecycle("GUEST_SERVICE_FRAMEWORK_BOUND", record, rebind ? "REBIND" : "BIND");
    }

    private void unbindService(Object data, Record record) throws Exception {
        Intent hostIntent = (Intent) field(data, "intent");
        Intent intent = decodeGuestIntent(hostIntent);
        boolean rebind = record.service.onUnbind(intent);
        if (rebind) unbindFinished(record.token, hostIntent, true);
        else serviceDone(record.token, SERVICE_DONE_EXECUTING_ANON, 0, 0);
        logLifecycle("GUEST_SERVICE_FRAMEWORK_UNBOUND", record, rebind ? "REBIND" : "UNBIND");
    }

    private void serviceArgs(Object data, Record record) throws Exception {
        Intent intent = decodeGuestIntent((Intent) field(data, "args"));
        int startId = intField(data, "startId");
        int flags = intField(data, "flags");
        int result = booleanField(data, "taskRemoved")
                ? START_TASK_REMOVED_COMPLETE
                : record.service.onStartCommand(intent, flags, startId);
        serviceDone(record.token, SERVICE_DONE_EXECUTING_START, startId, result);
        logLifecycle("GUEST_SERVICE_FRAMEWORK_STARTED", record, "START:" + startId);
    }

    private void stopService(IBinder token, Record record) throws Exception {
        synchronized (records) { records.remove(token); }
        mapRemove(servicesData, token);
        mapRemove(services, token);
        record.service.onDestroy();
        detach(record.service);
        // ActivityThread.handleStopService acknowledges STOP_SERVICE to AMS after
        // Service.onDestroy(). The bridge owns that callback, so omitting the
        // STOP acknowledgement leaves the StubService in AMS's executing set and
        // produces a delayed ANR even though the guest Service has already been
        // destroyed. VA/NBB preserve this framework completion edge as well.
        serviceDone(token, SERVICE_DONE_EXECUTING_STOP, 0, 0);
        logLifecycle("GUEST_SERVICE_FRAMEWORK_DESTROYED", record, "STOP");
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        synchronized (records) {
            for (Record record : new ArrayList<>(records.values())) {
                try { record.service.onDestroy(); } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
                try { detach(record.service); } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
                mapRemove(servicesData, record.token);
                mapRemove(services, record.token);
            }
            records.clear();
        }
        ArrayList<HostConnection> boundConnections;
        synchronized (hostConnections) {
            boundConnections = new ArrayList<>(hostConnections.values());
            hostConnections.clear();
        }
        for (HostConnection connection : boundConnections) {
            try { session.context.hostServiceContext().unbindService(connection); }
            catch (RuntimeException ignored) { }
        }
        try {
            if (callbackField.get(handler) == callback) callbackField.set(handler, previousCallback);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_SERVICE_FRAMEWORK", "restore callback failed", error);
        }
    }

    private Intent createIntent(Object data) {
        return (Intent) field(data, "intent");
    }

    private Intent lifecycleIntent(int what, Object data) {
        if (what == CREATE_SERVICE) return createIntent(data);
        if (what == BIND_SERVICE || what == UNBIND_SERVICE) return (Intent) field(data, "intent");
        if (what == SERVICE_ARGS) return (Intent) field(data, "args");
        return null;
    }

    private Intent decodeGuestIntent(Intent host) {
        return RuntimeIntentWireCodec.decode(host == null ? null : host.getExtras());
    }

    private boolean isFrameworkRoute(Intent intent) {
        return intent != null && intent.getBooleanExtra("frameworkServiceRoute", false);
    }

    private IBinder tokenFor(int what, Object data) {
        if (what == STOP_SERVICE) return data instanceof IBinder ? (IBinder) data : null;
        if (what == BIND_SERVICE || what == UNBIND_SERVICE || what == SERVICE_ARGS) {
            Object value = field(data, "token");
            return value instanceof IBinder ? (IBinder) value : null;
        }
        return null;
    }

    private ServiceInfo projectServiceInfo(Object data, String guestClass) {
        Object original = field(data, "info");
        ServiceInfo info = original instanceof ServiceInfo
                ? new ServiceInfo((ServiceInfo) original) : new ServiceInfo();
        info.name = guestClass;
        info.packageName = session.spec.packageName;
        info.processName = session.spec.processName;
        info.applicationInfo = new ApplicationInfo(session.context.getApplicationInfo());
        return info;
    }

    private Object serviceManagerProxy(ServiceInfo info) throws Exception {
        Class<?> managerType = Class.forName("android.app.IActivityManager");
        InvocationHandler handler = (proxy, method, args) -> {
            Object[] forwarded = args == null ? null : args.clone();
            if ("stopServiceToken".equals(method.getName()) && forwarded != null
                    && forwarded.length > 0) {
                forwarded[0] = new ComponentName(session.context.hostServiceContext(),
                        GuestServiceStubNames.classNameFor(session.spec.processSlot));
            }
            try {
                return method.invoke(activityManager, forwarded);
            } catch (java.lang.reflect.InvocationTargetException error) {
                throw error.getCause();
            }
        };
        return Proxy.newProxyInstance(managerType.getClassLoader(), new Class<?>[]{managerType}, handler);
    }

    private static Object activityManager() throws Exception {
        Class<?> type = Class.forName("android.app.ActivityManager");
        Method method = type.getDeclaredMethod("getService");
        method.setAccessible(true);
        return method.invoke(null);
    }

    private void serviceDone(IBinder token, int type, int startId, int result) throws Exception {
        invokeActivityManager("serviceDoneExecuting", token, type, startId, result);
    }

    private void publishService(IBinder token, Intent intent, IBinder binder) throws Exception {
        invokeActivityManager("publishService", token, intent, binder);
    }

    private void unbindFinished(IBinder token, Intent intent, boolean rebind) throws Exception {
        invokeActivityManager("unbindFinished", token, intent, rebind);
    }

    private void invokeActivityManager(String name, Object... args) throws Exception {
        Method target = null;
        for (Method candidate : activityManager.getClass().getMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterTypes().length == args.length) {
                target = candidate;
                break;
            }
        }
        if (target == null) throw new NoSuchMethodException(name);
        try { target.invoke(activityManager, args); }
        catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RemoteException) throw (RemoteException) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
    }

    private void logLifecycle(String name, Record record, String stage) {
        Bundle event = new Bundle();
        event.putString(RuntimeKeys.STATUS, stage);
        event.putBoolean("frameworkOwnedService", true);
        event.putString(RuntimeKeys.COMPONENT_CLASS, record.className);
        event.putString(RuntimeKeys.SESSION_ID, session.spec.sessionId);
        event.putLong(RuntimeKeys.GENERATION, session.spec.generation);
        event.putInt(RuntimeKeys.PROCESS_SLOT, session.spec.processSlot);
        RuntimeEventLog.event(name, event);
    }

    private static void detach(Service service) throws Exception {
        Method method = Service.class.getDeclaredMethod("detachAndCleanUp");
        method.setAccessible(true);
        method.invoke(service);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("SERVICE_FIELD_UNAVAILABLE:" + name, error);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean booleanField(Object target, String name) { return Boolean.TRUE.equals(field(target, name)); }
    private static int intField(Object target, String name) { return ((Number) field(target, name)).intValue(); }

    private static void mapPut(Object map, Object key, Object value) throws Exception {
        Method put = map.getClass().getMethod("put", Object.class, Object.class);
        put.setAccessible(true);
        put.invoke(map, key, value);
    }

    private static Object mapGet(Object map, Object key) {
        try {
            Method get = map.getClass().getMethod("get", Object.class);
            get.setAccessible(true);
            return get.invoke(map, key);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("SERVICE_MAP_GET_FAILED", error);
        }
    }

    private static void mapRemove(Object map, Object key) {
        try {
            Method remove = map.getClass().getMethod("remove", Object.class);
            remove.setAccessible(true);
            remove.invoke(map, key);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
        }
    }

    private static void copyString(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) target.putExtra(key, source.getString(key));
    }

    private static void copyInt(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) target.putExtra(key, source.getInt(key));
    }

    private static void copyStringList(Bundle source, Intent target, String key) {
        if (source != null && source.containsKey(key)) {
            ArrayList<String> values = source.getStringArrayList(key);
            if (values != null) target.putStringArrayListExtra(key, new ArrayList<>(values));
        }
    }

    private record Route(String sessionId, long generation, int slot, String packageName,
                         String guestClass, Bundle request) { }

    private static final class Record {
        final IBinder token;
        final Service service;
        final String className;
        final ServiceInfo info;
        final ComponentName stub;
        IBinder lastBinder;
        Record(IBinder token, Service service, String className, ServiceInfo info,
               ComponentName stub) {
            this.token = token;
            this.service = service;
            this.className = className;
            this.info = info;
            this.stub = stub;
        }
    }

    private final class HostConnection implements ServiceConnection {
        final Route route;
        final ServiceConnection guest;
        final Executor executor;
        boolean published;
        HostConnection(Route route, ServiceConnection guest, Executor executor) {
            this.route = route;
            this.guest = guest;
            this.executor = executor;
            synchronized (hostConnections) { hostConnections.put(guest, this); }
        }
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            android.util.Log.i("CS_SERVICE_FRAMEWORK", "HOST_CONNECTED guest=" + route.guestClass
                    + " binder=" + service);
            executor.execute(() -> guest.onServiceConnected(
                    new ComponentName(route.packageName, route.guestClass), service));
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            android.util.Log.i("CS_SERVICE_FRAMEWORK", "HOST_DISCONNECTED guest=" + route.guestClass);
            executor.execute(() -> guest.onServiceDisconnected(
                    new ComponentName(route.packageName, route.guestClass)));
        }
        @Override public void onBindingDied(ComponentName name) {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                executor.execute(() -> guest.onBindingDied(
                        new ComponentName(route.packageName, route.guestClass)));
            }
        }
        @Override public void onNullBinding(ComponentName name) {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.util.Log.i("CS_SERVICE_FRAMEWORK", "HOST_NULL_BINDING guest=" + route.guestClass);
                executor.execute(() -> guest.onNullBinding(
                        new ComponentName(route.packageName, route.guestClass)));
            }
        }
    }
}
