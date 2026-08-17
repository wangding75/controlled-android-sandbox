package com.warden.controlledsandbox.framework.core;

import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentProviderClient;
import android.net.Uri;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualGoogleServicesProfileSnapshot;
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
    private final List<ContentProviderClient> providerLeases;

    private SettingsProviderIdentityHook(List<ProviderReplacement> replacements,
                                         List<ContentProviderClient> providerLeases) {
        this.replacements = replacements;
        this.providerLeases = providerLeases;
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
        List<ContentProviderClient> providerLeases = new ArrayList<>();
        try {
            installNamespace(context, identity, device, settings, "Secure",
                    VirtualSettingsProfileSnapshot.NAMESPACE_SECURE, virtualAndroidId,
                    replacements, providerLeases);
            if (virtualSettings) {
                installNamespace(context, identity, device, settings, "System",
                        VirtualSettingsProfileSnapshot.NAMESPACE_SYSTEM, false,
                        replacements, providerLeases);
                installNamespace(context, identity, device, settings, "Global",
                        VirtualSettingsProfileSnapshot.NAMESPACE_GLOBAL, false,
                        replacements, providerLeases);
            }
            return new SettingsProviderIdentityHook(replacements, providerLeases);
        } catch (Throwable error) {
            for (int index = replacements.size() - 1; index >= 0; index--) replacements.get(index).restore();
            for (ContentProviderClient lease : providerLeases) try { lease.close(); } catch (Exception ignored) { }
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("VIRTUAL_SETTINGS_PROVIDER_INSTALL_FAILED", error);
        }
    }

    private static void installNamespace(Context context, GuestIdentity identity,
            VirtualDeviceIdentitySnapshot device, VirtualSettingsProfileSnapshot settings,
            String nestedName, String namespace, boolean virtualAndroidId,
            List<ProviderReplacement> replacements,
            List<ContentProviderClient> providerLeases) throws Exception {
        Class<?> owner = Class.forName("android.provider.Settings$" + nestedName);
        Field cacheField = ReflectiveServiceHook.findField(owner, "sNameValueCache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        if (cache == null) throw new IllegalStateException("Settings." + nestedName + " cache is unavailable");
        ProviderField provider = providerField(cache);
        Object original = provider.field.get(provider.owner);
        if (original == null && identity.isolatedProcess()) {
            original = syntheticProvider(provider.field.getType());
        }
        if (original == null) {
            original = acquireProvider(context, namespace, providerLeases);
            if (original != null) provider.field.set(provider.owner, original);
        }
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

    /**
     * ActivityManager rejects getContentProvider from isolated_app. Settings is already backed
     * by the virtual authority, so a capability process needs only the IContentProvider shape,
     * not a real provider lease. Unknown calls return framework-neutral defaults; virtual reads
     * and writes are handled by the outer proxy below.
     */
    private static Object syntheticProvider(Class<?> providerType) {
        if (!providerType.isInterface()) return null;
        ClassLoader loader = providerType.getClassLoader();
        if (loader == null) loader = SettingsProviderIdentityHook.class.getClassLoader();
        return Proxy.newProxyInstance(loader, new Class<?>[] {providerType}, (ignored, method, args) -> {
            if ("asBinder".equals(method.getName())) return new android.os.Binder();
            Class<?> returnType = method.getReturnType();
            if (!returnType.isPrimitive()) return null;
            if (returnType == boolean.class) return false;
            if (returnType == byte.class) return (byte) 0;
            if (returnType == short.class) return (short) 0;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0F;
            if (returnType == double.class) return 0D;
            if (returnType == char.class) return (char) 0;
            return null;
        });
    }

    private static Object acquireProvider(Context context, String namespace,
                                          List<ContentProviderClient> providerLeases) {
        try {
            Object resolver = context.getContentResolver();
            Uri uri = Uri.parse("content://settings/" + namespace.toLowerCase(Locale.ROOT));
            if (resolver instanceof ContentResolver contentResolver) {
                ContentProviderClient client = contentResolver.acquireContentProviderClient("settings");
                if (client != null) {
                    for (String fieldName : new String[] {"mContentProvider", "mProvider"}) {
                        try {
                            Field field = ReflectiveServiceHook.findField(client.getClass(), fieldName);
                            field.setAccessible(true);
                            Object value = field.get(client);
                            if (value != null) {
                                providerLeases.add(client);
                                return value;
                            }
                        } catch (NoSuchFieldException ignored) { }
                    }
                    try { client.close(); } catch (Exception ignored) { }
                }
            }
            Class<?> type = resolver.getClass();
            for (String methodName : new String[] {"acquireProvider", "acquireUnstableProvider"}) {
                Method method = null;
                Class<?> cursor = type;
                while (cursor != null && method == null) {
                    try { method = cursor.getDeclaredMethod(methodName, Uri.class); }
                    catch (NoSuchMethodException ignored) { cursor = cursor.getSuperclass(); }
                }
                if (method == null) continue;
                method.setAccessible(true);
                Object value = method.invoke(resolver, uri);
                if (value != null) return value;
            }
        } catch (Throwable ignored) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(ignored);
        }
        return null;
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

        if (VirtualSettingsProfileSnapshot.NAMESPACE_SECURE.equals(namespace)) {
            VirtualGoogleServicesProfileSnapshot google = null;
            try { google = identity.virtualServices().compatibilityProfile().googleServices(); }
            catch (IllegalStateException ignored) { }
            String googleValue = googleIdentityValue(google, key);
            if (googleValue != null) {
                if (put || delete) throw new SecurityException("VIRTUAL_GOOGLE_IDENTITY_MUTATION_DENIED:" + key);
                return resultBundle(key, googleValue);
            }
        }
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

    private static String googleIdentityValue(VirtualGoogleServicesProfileSnapshot google, String key) {
        if (google == null || VirtualLocationProfileSnapshot.MODE_HOST.equals(google.mode())) return null;
        boolean blocked = VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(google.mode());
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "advertising_id", "advertisingid" -> blocked ? "" : google.advertisingId();
            case "limit_ad_tracking", "limitadtracking" -> google.limitAdTracking() ? "1" : "0";
            case "app_set_id", "appsetid" -> blocked ? "" : google.appSetId();
            case "gsf_id", "gsfid" -> blocked ? "" : google.gsfId();
            case "firebase_installation_id", "firebaseinstallationid" -> blocked ? "" : google.installationId();
            default -> null;
        };
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
        for (ContentProviderClient lease : providerLeases) try { lease.close(); } catch (Exception ignored) { }
        providerLeases.clear();
    }

    private record ProviderField(Object owner, Field field) { }
    private record ProviderReplacement(Object owner, Field field, Object original) {
        void restore() { try { field.set(owner, original); } catch (Throwable ignored) { } }
    }
}
