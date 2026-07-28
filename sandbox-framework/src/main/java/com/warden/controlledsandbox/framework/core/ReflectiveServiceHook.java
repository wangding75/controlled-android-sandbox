package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
        Class<?> owner = Class.forName(ownerClassName);
        Field field = findField(owner, fieldName);
        field.setAccessible(true);
        Object original = field.get(null);
        if (original == null && initializerMethod != null && !initializerMethod.isEmpty()) {
            Method initializer = owner.getDeclaredMethod(initializerMethod);
            initializer.setAccessible(true);
            original = initializer.invoke(null);
        }
        return replace(null, field, original, identity, "");
    }

    public static ReflectiveServiceHook singleton(String ownerClassName, String singletonFieldName,
                                           GuestIdentity identity) throws Exception {
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
        return replace(singleton, instance, original, identity, "");
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
        return replace(owner, target, target.get(owner), identity, serviceName);
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
        Object proxy = createProxy(original, identity, serviceName);
        field.set(owner, proxy);
        return new ReflectiveServiceHook(owner, field, original);
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
