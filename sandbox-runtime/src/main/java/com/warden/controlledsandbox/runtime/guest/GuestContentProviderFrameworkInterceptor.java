package com.warden.controlledsandbox.runtime.guest;

import android.content.ContentProvider;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.framework.core.FrameworkCallInterceptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Supplies Broker-backed IContentProvider transports to the platform ContentResolver. */
final class GuestContentProviderFrameworkInterceptor implements FrameworkCallInterceptor, AutoCloseable {
    private final GuestContext context;
    private final GuestPackageSpec spec;
    private final Map<String, ProviderDescriptor> descriptors = new LinkedHashMap<>();
    private final Map<String, Object> holders = new LinkedHashMap<>();
    private final Map<String, GuestBrokerContentProvider> providers = new LinkedHashMap<>();

    GuestContentProviderFrameworkInterceptor(GuestContext context, GuestPackageSpec spec) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        for (VirtualComponentSnapshot component : spec.packageState.components()) {
            if (!"PROVIDER".equals(component.type()) || !component.enabled()) continue;
            for (String authority : component.authority().split(";")) {
                String normalized = authority == null ? "" : authority.trim();
                if (normalized.isEmpty()) continue;
                ProviderDescriptor prior = descriptors.put(normalized,
                        new ProviderDescriptor(normalized, component.className(), component.exported()));
                if (prior != null && !prior.componentClass.equals(component.className())) {
                    throw new IllegalStateException("DUPLICATE_PROVIDER_AUTHORITY:" + normalized);
                }
            }
        }
    }

    @Override public synchronized Interception intercept(
            String serviceName, Method method, Object[] arguments) throws Throwable {
        if (!"activity-manager".equals(serviceName) || method == null
                || !"getContentProvider".equals(method.getName())) {
            return Interception.passThrough();
        }
        String authority = authority(method, arguments);
        if (authority.isEmpty()) {
            throw new SecurityException("CONTENT_PROVIDER_AUTHORITY_UNRESOLVED");
        }
        ProviderDescriptor descriptor = descriptors.get(authority);
        if (descriptor == null) {
            throw new SecurityException("CONTENT_PROVIDER_AUTHORITY_NOT_VIRTUALIZED:" + authority);
        }
        Object holder = holders.get(authority);
        if (holder == null) {
            holder = createHolder(method.getReturnType(), descriptor);
            holders.put(authority, holder);
        }
        return Interception.handled(holder);
    }

    @Override public synchronized void close() {
        for (GuestBrokerContentProvider provider : providers.values()) {
            try { provider.shutdown(); }
            catch (Throwable ignored) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
            }
        }
        holders.clear();
        providers.clear();
    }

    private Object createHolder(Class<?> holderType, ProviderDescriptor descriptor) throws Exception {
        ProviderInfo info = providerInfo(descriptor);
        GuestBrokerContentProvider provider = new GuestBrokerContentProvider(
                context, spec, descriptor.authority, descriptor.componentClass);
        provider.attachInfo(context, info);
        provider.prepare();
        Method transportMethod = ContentProvider.class.getDeclaredMethod("getIContentProvider");
        transportMethod.setAccessible(true);
        Object transport = transportMethod.invoke(provider);
        if (transport == null) throw new IllegalStateException("CONTENT_PROVIDER_TRANSPORT_UNAVAILABLE");

        Object holder = instantiateHolder(holderType, info);
        setField(holder, "info", info, false);
        setField(holder, "provider", transport, true);
        setField(holder, "connection", null, false);
        setField(holder, "noReleaseNeeded", true, false);
        providers.put(descriptor.authority, provider);
        return holder;
    }

    private ProviderInfo providerInfo(ProviderDescriptor descriptor) {
        ProviderInfo info = new ProviderInfo();
        info.packageName = spec.packageName;
        info.name = descriptor.componentClass;
        info.authority = descriptor.authority;
        info.exported = descriptor.exported;
        info.enabled = true;
        info.processName = processName(descriptor.componentClass);
        info.applicationInfo = new ApplicationInfo(context.getApplicationInfo());
        return info;
    }

    private String processName(String componentClass) {
        for (VirtualComponentSnapshot component : spec.packageState.components()) {
            if (componentClass.equals(component.className())) {
                return component.processName().isEmpty() ? spec.packageName : component.processName();
            }
        }
        return spec.packageName;
    }

    private static Object instantiateHolder(Class<?> holderType, ProviderInfo info) throws Exception {
        if (holderType == null || holderType == Void.TYPE) {
            holderType = Class.forName("android.app.ContentProviderHolder");
        }
        for (Constructor<?> constructor : holderType.getDeclaredConstructors()) {
            constructor.setAccessible(true);
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 1 && ProviderInfo.class.isAssignableFrom(types[0])) {
                return constructor.newInstance(info);
            }
            if (types.length == 0) return constructor.newInstance();
        }
        throw new IllegalStateException("CONTENT_PROVIDER_HOLDER_CONSTRUCTOR_UNAVAILABLE");
    }

    private static void setField(Object target, String name, Object value, boolean required)
            throws IllegalAccessException {
        Class<?> cursor = target.getClass();
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        if (required) throw new IllegalStateException("CONTENT_PROVIDER_HOLDER_FIELD_MISSING:" + name);
    }

    private String authority(Method method, Object[] arguments) {
        if (arguments == null) return "";
        String known = knownAuthority(arguments);
        if (!known.isEmpty()) return known;
        String typed = authorityBeforeUserId(method, arguments);
        return typed.isEmpty() ? fallbackAuthority(arguments) : typed;
    }

    private String knownAuthority(Object[] arguments) {
        for (Object argument : arguments) {
            if (argument instanceof String value && descriptors.containsKey(value)) return value;
        }
        return "";
    }

    private String authorityBeforeUserId(Method method, Object[] arguments) {
        Class<?>[] parameterTypes = method == null ? new Class<?>[0] : method.getParameterTypes();
        int count = Math.min(parameterTypes.length, arguments.length);
        for (int index = 0; index < count; index++) {
            if (parameterTypes[index] != int.class && parameterTypes[index] != Integer.class) continue;
            String candidate = precedingString(parameterTypes, arguments, index);
            if (!candidate.isEmpty()) return candidate;
        }
        return "";
    }

    private String fallbackAuthority(Object[] arguments) {
        // Older test/vendor signatures can omit the user-id integer. The last non-caller
        // String is the only safe fallback and remains fail-closed for unknown authorities.
        for (int index = arguments.length - 1; index >= 0; index--) {
            Object argument = arguments[index];
            if (!(argument instanceof String)) continue;
            String value = ((String) argument).trim();
            if (!value.isEmpty() && !spec.packageName.equals(value)) return value;
        }
        return "";
    }

    private String precedingString(Class<?>[] types, Object[] arguments, int beforeIndex) {
        for (int index = beforeIndex - 1; index >= 0; index--) {
            if (types[index] != String.class || !(arguments[index] instanceof String)) continue;
            String value = ((String) arguments[index]).trim();
            if (!value.isEmpty() && !spec.packageName.equals(value)) return value;
        }
        return "";
    }

    private record ProviderDescriptor(String authority, String componentClass, boolean exported) { }
}
