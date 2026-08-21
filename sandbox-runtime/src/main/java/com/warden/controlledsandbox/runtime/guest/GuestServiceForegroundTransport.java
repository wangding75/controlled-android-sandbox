package com.warden.controlledsandbox.runtime.guest;

import android.content.ComponentName;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;

import com.warden.controlledsandbox.runtime.component.service.GuestServiceStubNames;
import com.warden.controlledsandbox.framework.identity.VirtualNotificationNamespace;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.Map;

/** Real AMS-facing foreground-service transport for one Guest Service generation. */
final class GuestServiceForegroundTransport implements AutoCloseable {
    private final GuestRuntimeEnvironment.Session session;
    private final Object activityManager;
    private final GuestRuntimeBrokerBridge routeBroker;
    private final Map<IBinder, Integer> foregroundTypes = new IdentityHashMap<>();
    private final Map<IBinder, ForegroundCall> foregroundCalls = new IdentityHashMap<>();
    private volatile boolean closed;

    GuestServiceForegroundTransport(GuestRuntimeEnvironment.Session session,
                                    Object activityManager,
                                    GuestRuntimeBrokerBridge routeBroker) {
        if (session == null || activityManager == null || routeBroker == null) {
            throw new IllegalArgumentException("foreground transport dependencies are required");
        }
        this.session = session;
        this.activityManager = activityManager;
        this.routeBroker = routeBroker;
    }

    void initialize(IBinder token) {
        if (closed) return;
        synchronized (foregroundTypes) {
            if (closed) return;
            foregroundTypes.put(token, 0);
            foregroundCalls.remove(token);
        }
    }

    void clear(IBinder token) {
        synchronized (foregroundTypes) {
            foregroundTypes.remove(token);
            foregroundCalls.remove(token);
        }
    }

    Bundle recoverySnapshot(IBinder token) {
        if (closed) return null;
        ForegroundCall call;
        synchronized (foregroundTypes) { call = foregroundCalls.get(token); }
        if (call == null || !call.promote) return null;
        Bundle result = new Bundle();
        result.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_OBSERVED, true);
        result.putInt(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED_TYPE_MASK, call.typeMask);
        result.putInt(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_ID, call.notificationId);
        result.putString(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_TAG, "");
        return result;
    }

    Object serviceManagerProxy(ServiceInfo info, IBinder token, boolean recovery) throws Exception {
        Class<?> managerType = Class.forName("android.app.IActivityManager");
        InvocationHandler handler = (proxy, method, args) -> {
            // Service.onDestroy() and framework-owned teardown can race callbacks posted by
            // WebView/Chromium or an app worker.  Do not let a stale Service manager re-enter
            // host AMS after this generation has been retired.  The terminal result matches the
            // Binder-shaped method contract while keeping the teardown edge idempotent.
            if (closed) {
                android.util.Log.i("CS_SERVICE_FRAMEWORK",
                        "late Service manager call ignored after transport teardown method="
                                + method.getName());
                return defaultReturn(method.getReturnType());
            }
            if ("setServiceForeground".equals(method.getName())) {
                return setFrameworkServiceForeground(info, token, method, args, recovery);
            }
            if ("getForegroundServiceType".equals(method.getName())) {
                return getFrameworkServiceForegroundType(info, token, method, args);
            }
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

    /**
     * Bridges Service.startForeground/stopForeground through both sides of the framework edge.
     * The host AMS must receive the real Stub component name, while the Broker must receive the
     * Guest component and generation so foreground state, notification ownership and recovery
     * remain virtual-user scoped.
     */
    private Object setFrameworkServiceForeground(ServiceInfo info, IBinder token,
                                                  Method method, Object[] args,
                                                  boolean recovery) throws Throwable {
        if (closed) return defaultReturn(method.getReturnType());
        ForegroundCall call = ForegroundCall.decode(method, args, info);
        Bundle request = routeBroker.baseRequest();
        request.putString(ComponentOperations.OPERATION,
                ComponentOperations.SET_SERVICE_FOREGROUND);
        request.putString(RuntimeKeys.COMPONENT_CLASS, info.name);
        request.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        request.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, call.promote);
        request.putInt(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED_TYPE_MASK, call.typeMask);
        request.putInt(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_ID, call.notificationId);
        request.putString(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_TAG, "");
        request.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_REMOVE_NOTIFICATION, call.removeNotification);
        request.putBoolean(RuntimeKeys.SERVICE_RECOVERY, recovery);
        request.putInt(RuntimeKeys.SERVICE_FOREGROUND_DECLARED_TYPE_MASK,
                infoForegroundType(info));

        // Promote in the virtual state first so invalid Guest metadata never causes the host
        // Stub to become foreground. A host rejection is rolled back through the same typed
        // Broker operation before the exception reaches Service.startForeground().
        Bundle state = routeBroker.invokeComponent(request);
        Object[] forwarded = args == null ? null : args.clone();
        rewriteFrameworkServiceComponent(forwarded);
        Runnable restoreNotification = rewriteForegroundNotificationChannel(forwarded);
        try {
            Object result = method.invoke(activityManager, forwarded);
            synchronized (foregroundTypes) {
                foregroundTypes.put(token, call.promote ? call.typeMask : 0);
                if (call.promote) foregroundCalls.put(token, call);
                else foregroundCalls.remove(token);
            }
            restoreNotification.run();
            return result;
        } catch (java.lang.reflect.InvocationTargetException error) {
            restoreNotification.run();
            rollbackFrameworkForeground(info, call);
            throw error.getCause();
        } catch (Throwable error) {
            restoreNotification.run();
            rollbackFrameworkForeground(info, call);
            throw error;
        }
    }

    /**
     * NotificationManager creates the real host channel under the virtual namespace, while the
     * Guest Notification object still carries the Guest-visible channel ID.  AMS validates the
     * object when it receives setServiceForeground, so project only that nested field for the
     * host call and restore it before control returns to the Guest Service.
     */
    private Runnable rewriteForegroundNotificationChannel(Object[] args) {
        if (args == null) return () -> { };
        for (Object value : args) {
            if (value == null || !"android.app.Notification".equals(value.getClass().getName())) {
                continue;
            }
            Field field = findOptionalField(value.getClass(), "mChannelId", "channelId");
            if (field == null) return () -> { };
            try {
                field.setAccessible(true);
                Object raw = field.get(value);
                if (!(raw instanceof String guestChannel) || guestChannel.isEmpty()) {
                    return () -> { };
                }
                String hostChannel = VirtualNotificationNamespace.hostChannelId(
                        session.spec.packageName, session.spec.virtualUserId, guestChannel);
                field.set(value, hostChannel);
                return () -> {
                    try {
                        field.set(value, raw);
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                                .rethrowIfFatal(error);
                        android.util.Log.e("CS_SERVICE_FRAMEWORK",
                                "foreground notification channel restore failed", error);
                    }
                };
            } catch (ReflectiveOperationException | RuntimeException error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                throw new IllegalStateException("FRAMEWORK_FOREGROUND_NOTIFICATION_CHANNEL_REWRITE_FAILED",
                        error);
            }
        }
        return () -> { };
    }

    private Object getFrameworkServiceForegroundType(ServiceInfo info, IBinder token,
                                                      Method method, Object[] args) throws Throwable {
        if (closed) return defaultReturn(method.getReturnType());
        synchronized (foregroundTypes) {
            if (foregroundTypes.containsKey(token)) {
                return foregroundTypes.get(token);
            }
        }
        Object[] forwarded = args == null ? null : args.clone();
        rewriteFrameworkServiceComponent(forwarded);
        try {
            return method.invoke(activityManager, forwarded);
        } catch (java.lang.reflect.InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private void rollbackFrameworkForeground(ServiceInfo info, ForegroundCall call) {
        if (!call.promote) return;
        try {
            Bundle rollback = routeBroker.baseRequest();
            rollback.putString(ComponentOperations.OPERATION,
                    ComponentOperations.SET_SERVICE_FOREGROUND);
            rollback.putString(RuntimeKeys.COMPONENT_CLASS, info.name);
            rollback.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
            rollback.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, false);
            rollback.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_REMOVE_NOTIFICATION, true);
            routeBroker.invokeComponent(rollback);
        } catch (Throwable rollbackFailure) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(rollbackFailure);
            android.util.Log.e("CS_SERVICE_FRAMEWORK",
                    "foreground rollback failed component=" + info.name, rollbackFailure);
        }
    }

    private void rewriteFrameworkServiceComponent(Object[] args) {
        if (args == null) return;
        ComponentName stub = new ComponentName(session.context.hostServiceContext(),
                GuestServiceStubNames.classNameFor(session.spec.processSlot));
        for (int index = 0; index < args.length; index++) {
            if (args[index] instanceof ComponentName) {
                args[index] = stub;
                return;
            }
        }
    }

    private static int infoForegroundType(ServiceInfo info) {
        return optionalIntField(info, "foregroundServiceType");
    }


    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Field findOptionalField(Class<?> type, String... names) {
        for (String name : names) {
            try { return findField(type, name); }
            catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    private static int optionalIntField(Object target, String name) {
        if (target == null) return 0;
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        synchronized (foregroundTypes) {
            foregroundTypes.clear();
            foregroundCalls.clear();
        }
    }

    private static Object defaultReturn(Class<?> type) {
        if (type == null || type == void.class) return null;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == byte.class || type == Byte.class) return (byte) 0;
        if (type == short.class || type == Short.class) return (short) 0;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == float.class || type == Float.class) return 0F;
        if (type == double.class || type == Double.class) return 0D;
        if (type == char.class || type == Character.class) return (char) 0;
        return null;
    }

    private record ForegroundCall(boolean promote, int notificationId, int typeMask,
                                  boolean removeNotification) {
        static ForegroundCall decode(Method method, Object[] args, ServiceInfo info) {
            Class<?>[] types = method.getParameterTypes();
            int notificationIndex = -1;
            for (int index = 0; index < types.length; index++) {
                if ("android.app.Notification".equals(types[index].getName())) {
                    notificationIndex = index;
                    break;
                }
            }
            boolean promote = notificationIndex >= 0
                    ? args != null && args.length > notificationIndex && args[notificationIndex] != null
                    : firstBoolean(args, types, false);
            int notificationId = -1;
            int idIndex = lastIntegerBefore(args, types, notificationIndex);
            if (idIndex >= 0) notificationId = ((Number) args[idIndex]).intValue();

            java.util.ArrayList<Integer> integerAfter = integerIndexesAfter(args, types,
                    notificationIndex);
            int typeMask = integerAfter.size() >= 2
                    ? ((Number) args[integerAfter.get(integerAfter.size() - 1)]).intValue()
                    : 0;
            boolean remove = true;
            if (!integerAfter.isEmpty()) {
                int flags = ((Number) args[integerAfter.get(0)]).intValue();
                remove = (flags & 1) != 0;
            }
            for (int index = Math.max(0, notificationIndex + 1);
                    args != null && index < args.length && index < types.length; index++) {
                if (types[index] == boolean.class || types[index] == Boolean.class) {
                    // Legacy IActivityManager used keepNotification rather than stop flags.
                    remove = !Boolean.TRUE.equals(args[index]);
                }
            }
            if (promote && notificationId < 0) {
                throw new IllegalArgumentException("FRAMEWORK_FOREGROUND_NOTIFICATION_ID_MISSING");
            }
            return new ForegroundCall(promote, notificationId, Math.max(0, typeMask), remove);
        }

        private static int lastIntegerBefore(Object[] args, Class<?>[] types, int before) {
            int limit = before < 0 ? types.length : before;
            for (int index = Math.min(limit, types.length) - 1; index >= 0; index--) {
                if (args != null && index < args.length && args[index] instanceof Number
                        && (types[index] == int.class || types[index] == Integer.class)) {
                    return index;
                }
            }
            return -1;
        }

        private static java.util.ArrayList<Integer> integerIndexesAfter(
                Object[] args, Class<?>[] types, int after) {
            java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
            int start = Math.max(0, after + 1);
            for (int index = start; index < types.length; index++) {
                if (args != null && index < args.length && args[index] instanceof Number
                        && (types[index] == int.class || types[index] == Integer.class)) {
                    result.add(index);
                }
            }
            return result;
        }

        private static boolean firstBoolean(Object[] args, Class<?>[] types, boolean fallback) {
            for (int index = 0; index < types.length; index++) {
                if ((types[index] == boolean.class || types[index] == Boolean.class)
                        && args != null && index < args.length) {
                    return Boolean.TRUE.equals(args[index]);
                }
            }
            return fallback;
        }
    }


}
