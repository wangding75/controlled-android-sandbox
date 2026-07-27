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
        return replaceField(manager, fieldName, identity);
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
        return replace(null, field, original, identity);
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
        return replace(singleton, instance, original, identity);
    }

    static ReflectiveServiceHook replaceField(Object owner, String fieldName, GuestIdentity identity) throws Exception {
        Field field = findField(owner.getClass(), fieldName);
        field.setAccessible(true);
        return replace(owner, field, field.get(owner), identity);
    }

    static Object createProxy(Object original, GuestIdentity identity) {
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
                new SystemServiceInvocationHandler(original, identity));
    }

    private static ReflectiveServiceHook replace(Object owner, Field field, Object original,
                                                 GuestIdentity identity) throws Exception {
        Object proxy = createProxy(original, identity);
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
