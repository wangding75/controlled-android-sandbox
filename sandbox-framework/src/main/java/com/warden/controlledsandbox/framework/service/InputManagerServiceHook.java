package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.InputManagerServiceContract;
import com.warden.controlledsandbox.framework.core.GuestSystemServiceOverrideRegistry;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import android.content.Context;
import android.os.IBinder;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Installs the bounded virtual input service used by framework clients such as WebView.
 *
 * <p>InputManager is a Java facade over a hidden IInputManager Binder.  Replacing only the
 * public facade would leave InputManagerGlobal/InputManager's static cache able to retain the
 * Host transport, so this hook audits and replaces the Binder cache and then creates a separate
 * Guest facade.  The facade exposes an empty virtual device catalog and controlled defaults;
 * raw Binder transactions and all mutating operations are fail-closed.</p>
 */
public final class InputManagerServiceHook implements AutoCloseable {
    private final Map<String, Object> cache;
    private final Object originalBinder;
    private final Object replacementBinder;
    private final Context guestContext;
    private final AutoCloseable override;
    private final Field cacheField;
    private final Object originalManagerCache;
    private final Object replacementManagerCache;

    private InputManagerServiceHook(
            Map<String, Object> cache,
            Object originalBinder,
            Object replacementBinder,
            Context guestContext,
            AutoCloseable override,
            Field cacheField,
            Object originalManagerCache,
            Object replacementManagerCache) {
        this.cache = cache;
        this.originalBinder = originalBinder;
        this.replacementBinder = replacementBinder;
        this.guestContext = guestContext;
        this.override = override;
        this.cacheField = cacheField;
        this.originalManagerCache = originalManagerCache;
        this.replacementManagerCache = replacementManagerCache;
    }

    public static AutoCloseable install(Context guestContext, GuestIdentity identity)
            throws Exception {
        if (guestContext == null) throw new IllegalArgumentException("guestContext is required");

        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Field serviceCacheField = findField(serviceManager, "sCache");
        serviceCacheField.setAccessible(true);
        Object serviceCacheObject = serviceCacheField.get(null);
        if (!(serviceCacheObject instanceof Map)) {
            throw new IllegalStateException("ServiceManager cache unavailable");
        }
        @SuppressWarnings("unchecked") Map<String, Object> serviceCache =
                (Map<String, Object>) serviceCacheObject;

        Object originalBinder;
        synchronized (serviceCache) {
            originalBinder = serviceCache.get(InputManagerServiceContract.ANDROID_SERVICE);
        }
        if (originalBinder == null) {
            originalBinder = getService.invoke(null, InputManagerServiceContract.ANDROID_SERVICE);
        }
        if (!(originalBinder instanceof IBinder binder)) {
            throw new IllegalStateException("System service unavailable: "
                    + InputManagerServiceContract.ANDROID_SERVICE);
        }
        String descriptor = binder.getInterfaceDescriptor();
        if (!InputManagerServiceContract.DESCRIPTOR.equals(descriptor)) {
            throw new IllegalStateException("Unexpected Binder descriptor for input: " + descriptor);
        }

        Class<?> stub = Class.forName(InputManagerServiceContract.DESCRIPTOR + "$Stub");
        Method asInterface = findAsInterface(stub);
        Object originalService = asInterface.invoke(null, binder);
        if (originalService == null) throw new IllegalStateException("Input service interface unavailable");
        Class<?> serviceInterface = findServiceInterface(originalService.getClass());
        if (serviceInterface == null) throw new IllegalStateException("Input service interface not found");

        Object[] binderHolder = new Object[1];
        Object controlledService = Proxy.newProxyInstance(
                proxyLoader(originalService.getClass()),
                new Class<?>[] {serviceInterface},
                new ControlledInputServiceInvocationHandler(binderHolder));
        IBinder replacementBinder = (IBinder) Proxy.newProxyInstance(
                proxyLoader(originalBinder.getClass()),
                new Class<?>[] {IBinder.class},
                new ControlledInputBinderInvocationHandler(
                        binder, InputManagerServiceContract.DESCRIPTOR, controlledService));
        binderHolder[0] = replacementBinder;

        synchronized (serviceCache) {
            serviceCache.put(InputManagerServiceContract.ANDROID_SERVICE, replacementBinder);
        }

        ManagerCacheState managerState = installGuestManager(guestContext, controlledService);
        AutoCloseable override = null;
        try {
            override = GuestSystemServiceOverrideRegistry.install(
                    guestContext, InputManagerServiceContract.ANDROID_SERVICE, managerState.guestManager);
            return new InputManagerServiceHook(serviceCache, originalBinder, replacementBinder,
                    guestContext, override, managerState.field, managerState.original,
                    managerState.replacement);
        } catch (Throwable error) {
            managerState.restore();
            synchronized (serviceCache) {
                if (serviceCache.get(InputManagerServiceContract.ANDROID_SERVICE) == replacementBinder) {
                    serviceCache.put(InputManagerServiceContract.ANDROID_SERVICE, originalBinder);
                }
            }
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("INPUT_MANAGER_GUEST_FACADE_FAILED", error);
        }
    }

    private static ManagerCacheState installGuestManager(Context guestContext, Object service)
            throws Exception {
        try {
            Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
            Field field = findField(global, "sInstance");
            field.setAccessible(true);
            Object original = field.get(null);
            field.set(null, null);
            try {
                Class<?> managerClass = Class.forName("android.hardware.input.InputManager");
                Constructor<?> constructor = findContextConstructor(managerClass);
                Object manager = constructor.newInstance(guestContext);
                Object replacement = field.get(null);
                if (replacement == null) {
                    throw new IllegalStateException("InputManagerGlobal did not initialize");
                }
                return new ManagerCacheState(field, original, replacement, manager);
            } catch (Throwable error) {
                if (field.get(null) == null) field.set(null, original);
                if (error instanceof Exception exception) throw exception;
                throw new IllegalStateException("INPUT_MANAGER_GUEST_FACADE_FAILED", error);
            }
        } catch (ClassNotFoundException api32Shape) {
            Class<?> managerClass = Class.forName("android.hardware.input.InputManager");
            Field field = findField(managerClass, "sInstance");
            field.setAccessible(true);
            Object original = field.get(null);
            Object manager = instantiateApi32Manager(managerClass, service);
            field.set(null, manager);
            return new ManagerCacheState(field, original, manager, manager);
        }
    }

    private static Constructor<?> findContextConstructor(Class<?> managerClass)
            throws NoSuchMethodException {
        for (Constructor<?> constructor : managerClass.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && Context.class.isAssignableFrom(parameters[0])) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        throw new NoSuchMethodException(managerClass.getName() + "(Context)");
    }

    private static Object instantiateApi32Manager(Class<?> managerClass, Object service)
            throws Exception {
        for (Method method : managerClass.getDeclaredMethods()) {
            if (!"resetInstance".equals(method.getName()) || method.getParameterCount() != 1) continue;
            method.setAccessible(true);
            return method.invoke(null, service);
        }
        for (Constructor<?> constructor : managerClass.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 1
                    && constructor.getParameterTypes()[0].isInstance(service)) {
                constructor.setAccessible(true);
                return constructor.newInstance(service);
            }
        }
        throw new NoSuchMethodException(managerClass.getName() + ".resetInstance");
    }

    private static Class<?> findServiceInterface(Class<?> type) {
        Class<?> cursor = type;
        while (cursor != null) {
            for (Class<?> candidate : cursor.getInterfaces()) {
                if (InputManagerServiceContract.DESCRIPTOR.equals(candidate.getName())) return candidate;
                Class<?> nested = findServiceInterface(candidate);
                if (nested != null) return nested;
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private static Method findAsInterface(Class<?> stub) throws NoSuchMethodException {
        for (Method method : stub.getDeclaredMethods()) {
            if ("asInterface".equals(method.getName()) && method.getParameterCount() == 1) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(stub.getName() + ".asInterface");
    }

    private static ClassLoader proxyLoader(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null ? InputManagerServiceHook.class.getClassLoader() : loader;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    @Override public void close() {
        try { override.close(); } catch (Throwable ignored) { }
        try {
            if (cacheField.get(null) == replacementManagerCache) {
                cacheField.set(null, originalManagerCache);
            }
        } catch (Throwable ignored) { }
        synchronized (cache) {
            if (cache.get(InputManagerServiceContract.ANDROID_SERVICE) == replacementBinder) {
                if (originalBinder == null) cache.remove(InputManagerServiceContract.ANDROID_SERVICE);
                else cache.put(InputManagerServiceContract.ANDROID_SERVICE, originalBinder);
            }
        }
    }

    private static final class ManagerCacheState {
        final Field field;
        final Object original;
        final Object replacement;
        final Object guestManager;

        ManagerCacheState(Field field, Object original, Object replacement, Object guestManager) {
            this.field = field;
            this.original = original;
            this.replacement = replacement;
            this.guestManager = guestManager;
        }

        void restore() {
            try {
                if (field.get(null) == replacement) field.set(null, original);
            } catch (Throwable ignored) { }
        }
    }

    private static final class ControlledInputServiceInvocationHandler
            implements InvocationHandler {
        private final Object[] binderHolder;

        ControlledInputServiceInvocationHandler(Object[] binderHolder) {
            this.binderHolder = binderHolder;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "VirtualInputManagerService";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> null;
                };
            }
            if ("asBinder".equals(method.getName())) return binderHolder[0];
            return InputManagerServiceContract.controlledResult(method.getName(), method.getReturnType());
        }
    }

    private static final class ControlledInputBinderInvocationHandler
            implements InvocationHandler {
        private final IBinder original;
        private final String descriptor;
        private final Object service;

        ControlledInputBinderInvocationHandler(IBinder original, String descriptor, Object service) {
            this.original = original;
            this.descriptor = descriptor;
            this.service = service;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "VirtualBinder[" + descriptor + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> null;
                };
            }
            return switch (method.getName()) {
                case "getInterfaceDescriptor" -> descriptor;
                case "queryLocalInterface" -> args != null && args.length == 1
                        && descriptor.equals(args[0]) ? service : null;
                case "isBinderAlive", "pingBinder" -> true;
                case "linkToDeath" -> null;
                case "unlinkToDeath" -> true;
                case "transact" -> false;
                default -> defaultBinderResult(method.getReturnType());
            };
        }

        private static Object defaultBinderResult(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == void.class) return null;
            return null;
        }
    }
}
