package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/** Reversible ServiceManager cache hook returning a local Binder interface proxy. */
public final class ServiceManagerBinderHook implements AutoCloseable {
    private final Map<Object, Object> cache;
    private final Object key;
    private final Object original;
    private final Object replacement;

    private ServiceManagerBinderHook(
            Map<Object, Object> cache,
            Object key,
            Object original,
            Object replacement) {
        this.cache = cache;
        this.key = key;
        this.original = original;
        this.replacement = replacement;
    }

    @SuppressWarnings("unchecked")
    public static AutoCloseable install(
            String androidServiceName,
            String stubClassName,
            GuestIdentity identity,
            String logicalServiceName) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Field cacheField = ReflectiveServiceHook.findField(serviceManager, "sCache");
        cacheField.setAccessible(true);
        Map<Object, Object> cache = (Map<Object, Object>) cacheField.get(null);
        if (cache == null) {
            throw new IllegalStateException("ServiceManager cache unavailable");
        }

        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Object original = cache.get(androidServiceName);
        if (original == null) {
            original = getService.invoke(null, androidServiceName);
        }
        if (original == null) {
            throw new IllegalStateException("System service unavailable: " + androidServiceName);
        }

        Class<?> stub = Class.forName(stubClassName);
        Method asInterface = findAsInterface(stub);
        Object service = asInterface.invoke(null, original);
        if (service == null) {
            throw new IllegalStateException(
                    "Cannot resolve " + androidServiceName + " interface");
        }
        Object serviceProxy = ReflectiveServiceHook.createProxy(
                service, identity, logicalServiceName);

        Class<?> binderInterface = Class.forName("android.os.IBinder");
        ClassLoader loader = original.getClass().getClassLoader();
        if (loader == null) {
            loader = ServiceManagerBinderHook.class.getClassLoader();
        }
        Object delegate = original;
        Object binderProxy = Proxy.newProxyInstance(
                loader,
                new Class<?>[] {binderInterface},
                (proxy, method, args) -> {
                    if (method.getName().equals("queryLocalInterface")) {
                        return serviceProxy;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        synchronized (cache) {
            cache.put(androidServiceName, binderProxy);
        }
        return new ServiceManagerBinderHook(
                cache, androidServiceName, original, binderProxy);
    }

    public static AutoCloseable installDiscovered(
            String androidServiceName,
            GuestIdentity identity,
            String logicalServiceName) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Object binder = getService.invoke(null, androidServiceName);
        if (binder == null) {
            throw new IllegalStateException("System service unavailable: " + androidServiceName);
        }
        Method descriptorMethod = binder.getClass().getMethod("getInterfaceDescriptor");
        String descriptor = String.valueOf(descriptorMethod.invoke(binder));
        if (descriptor.isEmpty() || "null".equals(descriptor)) {
            throw new IllegalStateException(
                    "Interface descriptor unavailable: " + androidServiceName);
        }
        return install(androidServiceName, descriptor + "$Stub", identity, logicalServiceName);
    }

    private static Method findAsInterface(Class<?> stub) throws NoSuchMethodException {
        for (Method method : stub.getDeclaredMethods()) {
            if (method.getName().equals("asInterface") && method.getParameterCount() == 1) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(stub.getName() + ".asInterface");
    }

    @Override
    public void close() {
        synchronized (cache) {
            if (cache.get(key) == replacement) {
                cache.put(key, original);
            }
        }
    }
}
