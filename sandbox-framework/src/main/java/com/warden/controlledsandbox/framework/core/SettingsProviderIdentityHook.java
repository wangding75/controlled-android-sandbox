package com.warden.controlledsandbox.framework.core;

import android.content.Context;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Reversible Settings.Secure provider projection for android_id. */
public final class SettingsProviderIdentityHook implements AutoCloseable {
    private final Object owner;
    private final Field field;
    private final Object original;

    private SettingsProviderIdentityHook(Object owner, Field field, Object original) {
        this.owner = owner; this.field = field; this.original = original;
    }

    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        VirtualDeviceIdentitySnapshot profile = identity.virtualServices().deviceServiceProfile().identity();
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return () -> { };
        Class<?> secure = Class.forName("android.provider.Settings$Secure");
        Field cacheField = ReflectiveServiceHook.findField(secure, "sNameValueCache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        if (cache == null) throw new IllegalStateException("Settings.Secure cache is unavailable");
        ProviderField provider = providerField(cache);
        Object original = provider.field.get(provider.owner);
        if (original == null) {
            initializeSecureCache(context, secure);
            original = provider.field.get(provider.owner);
        }
        if (original == null) throw new IllegalStateException("Settings.Secure provider is unavailable");
        String androidId = VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())
                ? null : profile.androidId();
        Object proxy = proxy(original, androidId);
        provider.field.set(provider.owner, proxy);
        return new SettingsProviderIdentityHook(provider.owner, provider.field, original);
    }

    private static ProviderField providerField(Object cache) throws Exception {
        try {
            Field direct = ReflectiveServiceHook.findField(cache.getClass(), "mContentProvider");
            direct.setAccessible(true);
            return new ProviderField(cache, direct);
        } catch (NoSuchFieldException directMissing) {
            Field holderField = ReflectiveServiceHook.findField(cache.getClass(), "mProviderHolder");
            holderField.setAccessible(true);
            Object holder = holderField.get(cache);
            if (holder == null) throw new IllegalStateException("Settings provider holder is unavailable");
            Field provider = ReflectiveServiceHook.findField(holder.getClass(), "mContentProvider");
            provider.setAccessible(true);
            return new ProviderField(holder, provider);
        }
    }

    private static void initializeSecureCache(Context context, Class<?> secure) {
        try {
            Method resolver = Context.class.getMethod("getContentResolver");
            Object contentResolver = resolver.invoke(context);
            Class<?> resolverType = Class.forName("android.content.ContentResolver");
            Method getString = secure.getMethod("getString", resolverType, String.class);
            getString.invoke(null, contentResolver, "android_id");
        } catch (Throwable ignored) { }
    }

    private static Object proxy(Object original, String androidId) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        Class<?> cursor = original.getClass();
        while (cursor != null) {
            for (Class<?> value : cursor.getInterfaces()) interfaces.add(value);
            cursor = cursor.getSuperclass();
        }
        if (interfaces.isEmpty()) throw new IllegalStateException("Settings provider exposes no interfaces");
        ClassLoader loader = original.getClass().getClassLoader();
        if (loader == null) loader = SettingsProviderIdentityHook.class.getClassLoader();
        InvocationHandler handler = (ignored, method, args) -> invoke(original, method, args, androidId);
        return Proxy.newProxyInstance(loader, interfaces.toArray(new Class<?>[0]), handler);
    }

    private static Object invoke(Object original, Method method, Object[] args, String androidId)
            throws Throwable {
        if (method.getDeclaringClass() == Object.class) return method.invoke(original, args);
        if ("call".equals(method.getName())) {
            String operation = stringContaining(args, "secure");
            String key = exactString(args, "android_id");
            if (key != null) {
                if (operation != null && operation.toUpperCase(Locale.ROOT).startsWith("PUT_")) {
                    throw new SecurityException("VIRTUAL_ANDROID_ID_MUTATION_DENIED");
                }
                Bundle result = new Bundle();
                result.putString("name", "android_id");
                result.putString("value", androidId);
                result.putString("android_id", androidId);
                return result;
            }
        }
        try { return method.invoke(original, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    private static String exactString(Object[] values, String expected) {
        if (values != null) for (Object value : values) {
            if (value instanceof String text && expected.equalsIgnoreCase(text)) return text;
        }
        return null;
    }
    private static String stringContaining(Object[] values, String needle) {
        if (values != null) for (Object value : values) {
            if (value instanceof String text
                    && text.toLowerCase(Locale.ROOT).contains(needle)) return text;
        }
        return null;
    }

    @Override public void close() { try { field.set(owner, original); } catch (Throwable ignored) { } }
    private record ProviderField(Object owner, Field field) { }
}
