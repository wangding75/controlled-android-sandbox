package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/** Reversible proxy installer for framework manager fields and android.util.Singleton instances. */
public final class ReflectiveServiceHook implements AutoCloseable {
    private final Object fieldOwner;
    private final Field field;
    private final Object original;

    private ReflectiveServiceHook(Object fieldOwner, Field field, Object original) {
        this.fieldOwner = fieldOwner;
        this.field = field;
        this.original = original;
    }

    public static ReflectiveServiceHook managerField(Context context, String serviceName, String fieldName,
                                              GuestIdentity identity) throws Exception {
        Object manager = context.getSystemService(serviceName);
        if (manager == null) throw new IllegalStateException("System service unavailable: " + serviceName);
        return replaceField(manager, fieldName, identity, serviceName);
    }

    /**
     * Installs at the stable ServiceManager boundary for framework managers which deliberately
     * retain no per-instance IInterface field.  The replacement accepts only the expected Binder
     * descriptor and exposes the already-audited interface proxy through queryLocalInterface.
     */
    public static AutoCloseable serviceManagerBinding(String serviceName, String descriptor,
                                                      GuestIdentity identity) throws Exception {
        return serviceManagerBinding(serviceName, serviceName, descriptor, identity);
    }

    public static AutoCloseable serviceManagerBinding(
            String androidServiceName, String logicalServiceName, String descriptor,
            GuestIdentity identity) throws Exception {
        if (androidServiceName == null || androidServiceName.trim().isEmpty()) {
            throw new IllegalArgumentException("androidServiceName is required");
        }
        if (descriptor == null || descriptor.trim().isEmpty()) {
            throw new IllegalArgumentException("descriptor is required");
        }
        Class<?> managerClass = Class.forName("android.os.ServiceManager");
        Method getService = managerClass.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Object original = getService.invoke(null, androidServiceName);
        if (!(original instanceof android.os.IBinder)) {
            throw new IllegalStateException("ServiceManager binder unavailable: " + androidServiceName);
        }
        android.os.IBinder binder = (android.os.IBinder) original;
        validateBinderDescriptor(binder, descriptor, androidServiceName);
        Class<?> stub = Class.forName(descriptor + "$Stub");
        Object service = findAsInterface(stub).invoke(null, binder);
        Object serviceProxy = createProxy(service, identity, logicalServiceName);
        InvocationHandler binderHandler = (proxy, method, args) -> {
            if ("queryLocalInterface".equals(method.getName()) && args != null && args.length == 1
                    && descriptor.equals(args[0])) return serviceProxy;
            try { return method.invoke(binder, args); }
            catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
        };
        android.os.IBinder replacement = (android.os.IBinder) Proxy.newProxyInstance(
                binder.getClass().getClassLoader() == null ? ReflectiveServiceHook.class.getClassLoader()
                        : binder.getClass().getClassLoader(),
                new Class<?>[] {android.os.IBinder.class}, binderHandler);
        Field cacheField = findField(managerClass, "sCache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        if (!(cache instanceof Map)) throw new IllegalStateException("ServiceManager cache unavailable");
        @SuppressWarnings("unchecked") Map<String, Object> entries = (Map<String, Object>) cache;
        Object previous;
        synchronized (entries) {
            previous = entries.put(androidServiceName, replacement);
        }
        return new ServiceManagerBinding(entries, androidServiceName, previous, replacement);
    }

    /**
     * Prefer the stable ServiceManager boundary and retain a descriptor-checked legacy field
     * fallback for platform revisions which do not expose the service through that boundary.
     */
    public static AutoCloseable managerFieldCandidatesOrServiceManagerBinding(
            Context context, String androidServiceName, String logicalServiceName,
            String descriptor, GuestIdentity identity, String... fieldPaths) throws Exception {
        return managerFieldCandidatesOrServiceManagerBinding(context, androidServiceName,
                logicalServiceName, descriptor, identity,
                java.util.List.of(androidServiceName), fieldPaths);
    }

    public static AutoCloseable managerFieldCandidatesOrServiceManagerBinding(
            Context context, String androidServiceName, String logicalServiceName,
            String descriptor, GuestIdentity identity, java.util.List<String> serviceNames,
            String... fieldPaths) throws Exception {
        java.util.ArrayList<Throwable> failures = new java.util.ArrayList<>();
        for (String serviceName : serviceNames) {
            try {
                return serviceManagerBinding(serviceName, logicalServiceName, descriptor, identity);
            } catch (Throwable error) {
                failures.add(error);
            }
        }

        Object manager = context.getSystemService(androidServiceName);
        if (manager == null) {
            failures.add(new IllegalStateException(
                    "System service unavailable: " + androidServiceName));
        } else {
            for (String path : fieldPaths) {
                try {
                    return replacePath(manager, path, identity, logicalServiceName, descriptor);
                } catch (Throwable error) {
                    failures.add(error);
                }
            }
        }
        IllegalStateException failure = new IllegalStateException(
                "No supported Binder binding for " + logicalServiceName);
        for (Throwable error : failures) failure.addSuppressed(error);
        throw failure;
    }

    public static ReflectiveServiceHook managerFieldCandidates(
            Context context, String androidServiceName, String logicalServiceName,
            GuestIdentity identity, String... fieldPaths) throws Exception {
        Object manager = context.getSystemService(androidServiceName);
        if (manager == null) throw new IllegalStateException(
                "System service unavailable: " + androidServiceName);
        java.util.ArrayList<Throwable> failures = new java.util.ArrayList<>();
        for (String path : fieldPaths) {
            try { return replacePath(manager, path, identity, logicalServiceName); }
            catch (Throwable error) { failures.add(error); }
        }
        IllegalStateException failure = new IllegalStateException(
                "No supported Binder field for " + logicalServiceName);
        for (Throwable error : failures) failure.addSuppressed(error);
        throw failure;
    }

    public static ReflectiveServiceHook staticField(String ownerClassName, String fieldName,
                                             String initializerMethod, GuestIdentity identity) throws Exception {
        return staticField(ownerClassName, fieldName, initializerMethod, identity, "");
    }

    public static ReflectiveServiceHook staticField(String ownerClassName, String fieldName,
                                             String initializerMethod, GuestIdentity identity,
                                             String serviceName) throws Exception {
        Class<?> owner = Class.forName(ownerClassName);
        Field field = findField(owner, fieldName);
        field.setAccessible(true);
        Object original = field.get(null);
        if (original == null && initializerMethod != null && !initializerMethod.isEmpty()) {
            Method initializer = owner.getDeclaredMethod(initializerMethod);
            initializer.setAccessible(true);
            original = initializer.invoke(null);
        }
        return replace(null, field, original, identity, serviceName);
    }

    public static ReflectiveServiceHook staticInstanceFieldCandidates(
            String ownerClassName, String getterMethod, GuestIdentity identity,
            String serviceName, String... fieldPaths) throws Exception {
        Class<?> owner = Class.forName(ownerClassName);
        Method getter = owner.getDeclaredMethod(getterMethod);
        getter.setAccessible(true);
        Object instance = getter.invoke(null);
        if (instance == null) throw new IllegalStateException(ownerClassName + "." + getterMethod + " returned null");
        java.util.ArrayList<Throwable> failures = new java.util.ArrayList<>();
        for (String path : fieldPaths) {
            try { return replacePath(instance, path, identity, serviceName); }
            catch (Throwable error) { failures.add(error); }
        }
        IllegalStateException failure = new IllegalStateException("No supported Binder field for " + serviceName);
        for (Throwable error : failures) failure.addSuppressed(error);
        throw failure;
    }

    public static ReflectiveServiceHook singleton(String ownerClassName, String singletonFieldName,
                                           GuestIdentity identity) throws Exception {
        return singleton(ownerClassName, singletonFieldName, identity, "");
    }

    public static ReflectiveServiceHook singleton(String ownerClassName, String singletonFieldName,
                                           GuestIdentity identity, String serviceName) throws Exception {
        Class<?> owner = Class.forName(ownerClassName);
        Field singletonField = findField(owner, singletonFieldName);
        singletonField.setAccessible(true);
        Object singleton = singletonField.get(null);
        if (singleton == null) throw new IllegalStateException(singletonFieldName + " is null");
        Field instance = findField(singleton.getClass(), "mInstance");
        instance.setAccessible(true);
        Object original = instance.get(singleton);
        if (original == null) {
            Method get = singleton.getClass().getMethod("get");
            get.setAccessible(true);
            original = get.invoke(singleton);
        }
        return replace(singleton, instance, original, identity, serviceName);
    }

    static ReflectiveServiceHook replaceField(Object owner, String fieldName, GuestIdentity identity) throws Exception {
        return replaceField(owner, fieldName, identity, "");
    }

    static ReflectiveServiceHook replaceField(Object owner, String fieldName, GuestIdentity identity,
                                              String serviceName) throws Exception {
        Field field = findField(owner.getClass(), fieldName);
        field.setAccessible(true);
        return replace(owner, field, field.get(owner), identity, serviceName);
    }

    private static ReflectiveServiceHook replacePath(Object root, String path, GuestIdentity identity,
                                                     String serviceName) throws Exception {
        return replacePath(root, path, identity, serviceName, "");
    }

    private static ReflectiveServiceHook replacePath(Object root, String path, GuestIdentity identity,
                                                     String serviceName, String expectedDescriptor) throws Exception {
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("field path is required");
        String[] segments = path.split("\\.");
        Object owner = root;
        for (int index = 0; index < segments.length - 1; index++) {
            Field field = findField(owner.getClass(), segments[index]);
            field.setAccessible(true);
            owner = field.get(owner);
            if (owner == null) throw new IllegalStateException("Null service field segment: " + segments[index]);
        }
        Field target = findField(owner.getClass(), segments[segments.length - 1]);
        target.setAccessible(true);
        return replace(owner, target, target.get(owner), identity, serviceName, expectedDescriptor);
    }

    static Object createProxy(Object original, GuestIdentity identity) {
        return createProxy(original, identity, "");
    }

    static Object createProxy(Object original, GuestIdentity identity, String serviceName) {
        if (original == null) throw new IllegalStateException("Framework service is null");
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        Class<?> cursor = original.getClass();
        while (cursor != null) {
            for (Class<?> iface : cursor.getInterfaces()) interfaces.add(iface);
            cursor = cursor.getSuperclass();
        }
        if (interfaces.isEmpty()) throw new IllegalStateException("Framework service exposes no interfaces");
        ClassLoader loader = original.getClass().getClassLoader();
        if (loader == null) loader = ReflectiveServiceHook.class.getClassLoader();
        return Proxy.newProxyInstance(loader, interfaces.toArray(new Class<?>[0]),
                new SystemServiceInvocationHandler(original, identity, serviceName));
    }

    private static ReflectiveServiceHook replace(Object owner, Field field, Object original,
                                                 GuestIdentity identity, String serviceName) throws Exception {
        return replace(owner, field, original, identity, serviceName, "");
    }

    private static ReflectiveServiceHook replace(Object owner, Field field, Object original,
                                                 GuestIdentity identity, String serviceName,
                                                 String expectedDescriptor) throws Exception {
        if (expectedDescriptor != null && !expectedDescriptor.isEmpty()) {
            validateServiceDescriptor(original, expectedDescriptor, serviceName);
        }
        Object proxy = createProxy(original, identity, serviceName);
        field.set(owner, proxy);
        return new ReflectiveServiceHook(owner, field, original);
    }

    private static void validateServiceDescriptor(Object service, String expectedDescriptor,
                                                  String serviceName) throws Exception {
        if (service == null) {
            throw new IllegalStateException("Framework service is null: " + serviceName);
        }
        Object binder = service instanceof android.os.IBinder
                ? service
                : findPublicMethod(service.getClass(), "asBinder").invoke(service);
        if (!(binder instanceof android.os.IBinder)) {
            throw new IllegalStateException("Framework service has no Binder: " + serviceName);
        }
        validateBinderDescriptor((android.os.IBinder) binder, expectedDescriptor, serviceName);
    }

    private static void validateBinderDescriptor(android.os.IBinder binder, String expectedDescriptor,
                                                String serviceName) throws Exception {
        Method descriptorMethod = findPublicMethod(binder.getClass(), "getInterfaceDescriptor");
        descriptorMethod.setAccessible(true);
        Object value = descriptorMethod.invoke(binder);
        String actualDescriptor = value == null ? "" : String.valueOf(value);
        if (!expectedDescriptor.equals(actualDescriptor)) {
            throw new IllegalStateException("Unexpected Binder descriptor for " + serviceName
                    + ": " + actualDescriptor + " expected=" + expectedDescriptor);
        }
    }

    private static Method findPublicMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return type.getMethod(name, parameterTypes);
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

    private static final class ServiceManagerBinding implements AutoCloseable {
        private final Map<String, Object> cache;
        private final String key;
        private final Object original;
        private final Object replacement;

        private ServiceManagerBinding(Map<String, Object> cache, String key,
                                      Object original, Object replacement) {
            this.cache = cache;
            this.key = key;
            this.original = original;
            this.replacement = replacement;
        }

        @Override public void close() {
            synchronized (cache) {
                if (cache.get(key) != replacement) return;
                if (original == null) cache.remove(key);
                else cache.put(key, original);
            }
        }
    }

    @Override public void close() {
        try { field.set(fieldOwner, original); } catch (Throwable ignored) { }
    }

    static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
