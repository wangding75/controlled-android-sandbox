package com.warden.controlledsandbox.framework.core;

import android.content.Context;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingsProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reversible Settings provider projection for Android ID and virtual Secure/System/Global values. */
public final class SettingsProviderIdentityHook implements AutoCloseable {
    private final List<ProviderReplacement> replacements;

    private SettingsProviderIdentityHook(List<ProviderReplacement> replacements) {
        this.replacements = replacements;
    }

    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        VirtualDeviceIdentitySnapshot device = identity.virtualServices().deviceServiceProfile().identity();
        VirtualSettingsProfileSnapshot settings;
        try { settings = identity.virtualServices().applicationEnvironmentProfile().settings(); }
        catch (IllegalStateException unavailable) { settings = null; }
        boolean virtualAndroidId = !VirtualLocationProfileSnapshot.MODE_HOST.equals(device.mode());
        boolean virtualSettings = settings != null
                && !VirtualLocationProfileSnapshot.MODE_HOST.equals(settings.mode());
        if (!virtualAndroidId && !virtualSettings) return () -> { };
        List<ProviderReplacement> replacements = new ArrayList<>();
        try {
            installNamespace(context, identity, device, settings, "Secure",
                    VirtualSettingsProfileSnapshot.NAMESPACE_SECURE, virtualAndroidId, replacements);
            if (virtualSettings) {
                installNamespace(context, identity, device, settings, "System",
                        VirtualSettingsProfileSnapshot.NAMESPACE_SYSTEM, false, replacements);
                installNamespace(context, identity, device, settings, "Global",
                        VirtualSettingsProfileSnapshot.NAMESPACE_GLOBAL, false, replacements);
            }
            return new SettingsProviderIdentityHook(replacements);
        } catch (Throwable error) {
            for (int index = replacements.size() - 1; index >= 0; index--) replacements.get(index).restore();
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("VIRTUAL_SETTINGS_PROVIDER_INSTALL_FAILED", error);
        }
    }

    private static void installNamespace(Context context, GuestIdentity identity,
            VirtualDeviceIdentitySnapshot device, VirtualSettingsProfileSnapshot settings,
            String nestedName, String namespace, boolean virtualAndroidId,
            List<ProviderReplacement> replacements) throws Exception {
        Class<?> owner = Class.forName("android.provider.Settings$" + nestedName);
        Field cacheField = ReflectiveServiceHook.findField(owner, "sNameValueCache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        if (cache == null) throw new IllegalStateException("Settings." + nestedName + " cache is unavailable");
        ProviderField provider = providerField(cache);
        Object original = provider.field.get(provider.owner);
        if (original == null) {
            initializeCache(context, owner, namespace.equals(VirtualSettingsProfileSnapshot.NAMESPACE_SECURE)
                    ? "android_id" : "screen_brightness");
            original = provider.field.get(provider.owner);
        }
        if (original == null) throw new IllegalStateException("Settings." + nestedName + " provider is unavailable");
        Object proxy = proxy(original, identity, device, settings, namespace, virtualAndroidId);
        provider.field.set(provider.owner, proxy);
        replacements.add(new ProviderReplacement(provider.owner, provider.field, original));
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

    private static void initializeCache(Context context, Class<?> owner, String key) {
        try {
            Method resolver = Context.class.getMethod("getContentResolver");
            Object contentResolver = resolver.invoke(context);
            Class<?> resolverType = Class.forName("android.content.ContentResolver");
            Method getString = owner.getMethod("getString", resolverType, String.class);
            getString.invoke(null, contentResolver, key);
        } catch (Throwable ignored) { }
    }

    private static Object proxy(Object original, GuestIdentity identity,
            VirtualDeviceIdentitySnapshot device, VirtualSettingsProfileSnapshot settings,
            String namespace, boolean virtualAndroidId) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        Class<?> cursor = original.getClass();
        while (cursor != null) {
            for (Class<?> value : cursor.getInterfaces()) interfaces.add(value);
            cursor = cursor.getSuperclass();
        }
        if (interfaces.isEmpty()) throw new IllegalStateException("Settings provider exposes no interfaces");
        ClassLoader loader = original.getClass().getClassLoader();
        if (loader == null) loader = SettingsProviderIdentityHook.class.getClassLoader();
        InvocationHandler handler = (ignored, method, args) -> invoke(original, method, args,
                identity, device, settings, namespace, virtualAndroidId);
        return Proxy.newProxyInstance(loader, interfaces.toArray(new Class<?>[0]), handler);
    }

    private static Object invoke(Object original, Method method, Object[] args,
            GuestIdentity identity, VirtualDeviceIdentitySnapshot device,
            VirtualSettingsProfileSnapshot settings, String namespace, boolean virtualAndroidId)
            throws Throwable {
        if (method.getDeclaringClass() == Object.class) return method.invoke(original, args);
        if (!"call".equals(method.getName())) return invokeOriginal(original, method, args);
        String operation = operation(args, namespace);
        String key = settingKey(args, operation, namespace);
        if (key.isEmpty()) return invokeOriginal(original, method, args);
        String upper = operation.toUpperCase(Locale.ROOT);
        boolean get = upper.contains("GET") || (!upper.contains("PUT") && !upper.contains("DELETE"));
        boolean put = upper.contains("PUT") || upper.contains("INSERT") || upper.contains("UPDATE");
        boolean delete = upper.contains("DELETE") || upper.contains("REMOVE");

        if (VirtualSettingsProfileSnapshot.NAMESPACE_SECURE.equals(namespace)
                && "android_id".equalsIgnoreCase(key) && virtualAndroidId) {
            if (put || delete) throw new SecurityException("VIRTUAL_ANDROID_ID_MUTATION_DENIED");
            String androidId = VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(device.mode())
                    ? null : device.androidId();
            return resultBundle(key, androidId);
        }
        if (settings == null || VirtualLocationProfileSnapshot.MODE_HOST.equals(settings.mode())) {
            return invokeOriginal(original, method, args);
        }
        if (VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(settings.mode())
                || !settings.namespaceAllowed(namespace) || settings.keyBlocked(key)) {
            if (put || delete) throw new SecurityException("VIRTUAL_SETTINGS_MUTATION_DENIED:" + namespace + ":" + key);
            return resultBundle(key, null);
        }
        if (put) {
            String value = settingValue(args, key);
            if (!settings.writeAllowed(namespace)) {
                throw new SecurityException("VIRTUAL_SETTINGS_WRITE_DENIED:" + namespace + ":" + key);
            }
            identity.virtualServices().authority().putSetting(
                    new VirtualSettingSnapshot(namespace, key, value, System.currentTimeMillis()));
            return resultBundle(key, value);
        }
        if (delete) {
            if (!settings.writeAllowed(namespace)) {
                throw new SecurityException("VIRTUAL_SETTINGS_DELETE_DENIED:" + namespace + ":" + key);
            }
            identity.virtualServices().authority().deleteSetting(namespace, key);
            return resultBundle(key, null);
        }
        if (get) {
            VirtualSettingSnapshot value = identity.virtualServices().authority().setting(namespace, key);
            return resultBundle(key, value == null ? null : value.value());
        }
        throw new UnsupportedOperationException("VIRTUAL_SETTINGS_OPERATION_UNSUPPORTED:" + operation);
    }

    private static Object invokeOriginal(Object original, Method method, Object[] args) throws Throwable {
        try { return method.invoke(original, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    private static Bundle resultBundle(String key, String value) {
        Bundle result = new Bundle();
        result.putString("name", key);
        result.putString("value", value);
        result.putString(key, value);
        return result;
    }

    private static String operation(Object[] values, String namespace) {
        if (values != null) for (Object value : values) {
            if (value instanceof String text) {
                String lower = text.toLowerCase(Locale.ROOT);
                if (lower.contains(namespace) && (lower.contains("get") || lower.contains("put")
                        || lower.contains("delete") || lower.contains("remove") || lower.contains("update"))) {
                    return text;
                }
            }
        }
        return "GET_" + namespace;
    }

    private static String settingKey(Object[] values, String operation, String namespace) {
        if (values == null) return "";
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank()) continue;
            String lower = text.toLowerCase(Locale.ROOT);
            if (text.equals(operation) || lower.equals(namespace) || lower.startsWith("content://")) continue;
            if (lower.contains("settings") || lower.contains("callingpackage")) continue;
            return text;
        }
        Bundle extras = bundle(values);
        if (extras != null) {
            String name = extras.getString("name", "");
            if (!name.isBlank()) return name;
            String key = extras.getString("key", "");
            if (!key.isBlank()) return key;
        }
        return "";
    }

    private static String settingValue(Object[] values, String key) {
        Bundle extras = bundle(values);
        if (extras != null) {
            String value = extras.getString("value", null);
            if (value != null) return value;
        }
        boolean passedKey = false;
        if (values != null) for (Object value : values) {
            if (!(value instanceof String text)) continue;
            if (!passedKey && text.equals(key)) { passedKey = true; continue; }
            if (passedKey) return text;
        }
        return "";
    }

    private static Bundle bundle(Object[] values) {
        if (values != null) for (Object value : values) if (value instanceof Bundle bundle) return bundle;
        return null;
    }

    @Override public void close() {
        for (int index = replacements.size() - 1; index >= 0; index--) replacements.get(index).restore();
        replacements.clear();
    }

    private record ProviderField(Object owner, Field field) { }
    private record ProviderReplacement(Object owner, Field field, Object original) {
        void restore() { try { field.set(owner, original); } catch (Throwable ignored) { } }
    }
}
