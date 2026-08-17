package com.warden.controlledsandbox.runtime.guest;

import android.content.ContentProvider;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualProviderPathRuleSnapshot;
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
    /**
     * The framework hook can remain installed for a short interval while the guest generation
     * is being torn down.  Without an explicit fence, a late ActivityManager
     * getContentProvider() call could create a fresh broker transport after close(), effectively
     * resurrecting a provider from the dead generation.
     */
    private volatile boolean closed;

    GuestContentProviderFrameworkInterceptor(GuestContext context, GuestPackageSpec spec) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        addDescriptors(spec.packageState, spec.packageName, spec.virtualUserId, spec.virtualUid,
                spec.packageState.applicationInfo());
        // The Virtual PackageManager projection is already visibility-filtered by the
        // Package Authority. Registering these read-only descriptors here makes the normal
        // ContentResolver -> ActivityManager.getContentProvider() path work for an exported
        // provider in another virtual package without executing that APK in the caller process.
        for (VirtualPackageProjectionSnapshot projection : spec.packageUniverse) {
            if (projection == null) continue;
            VirtualPackageStateSnapshot state = projection.packageState();
            addDescriptors(state, state.packageName(), state.virtualUserId(), projection.virtualUid(),
                    projection.parsedApplicationInfo());
        }
    }

    private void addDescriptors(VirtualPackageStateSnapshot state, String packageName,
                                int virtualUserId, int virtualUid,
                                ApplicationInfo parsedApplicationInfo) {
        if (state == null || packageName == null || packageName.trim().isEmpty()) return;
        ApplicationInfo applicationInfo = parsedApplicationInfo == null
                ? state.applicationInfo() : new ApplicationInfo(parsedApplicationInfo);
        if (applicationInfo == null) applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = packageName;
        applicationInfo.uid = virtualUid;
        for (VirtualComponentSnapshot component : state.components()) {
            if (!"PROVIDER".equals(component.type()) || !component.enabled()) continue;
            for (String authority : component.authority().split(";")) {
                String normalized = authority == null ? "" : authority.trim();
                if (normalized.isEmpty()) continue;
                // Match Android package parsing: the first provider owns a duplicated authority;
                // a later malformed declaration must not replace or conflict with that owner.
                descriptors.putIfAbsent(normalized,
                        new ProviderDescriptor(packageName, virtualUserId, normalized,
                                component.className(), component.exported(),
                                component.processName().isEmpty() ? packageName : component.processName(),
                                component.readPermission(), component.writePermission(),
                                component.grantUriPermissions(),
                                component.providerPathRules(),
                                new ApplicationInfo(applicationInfo)));
            }
        }
    }

    @Override public synchronized Interception intercept(
            String serviceName, Method method, Object[] arguments) throws Throwable {
        if (closed) {
            throw new SecurityException("CONTENT_PROVIDER_TRANSPORT_CLOSED");
        }
        if (!"activity-manager".equals(serviceName) || method == null
                || !"getContentProvider".equals(method.getName())) {
            return Interception.passThrough();
        }
        String authority = authority(method, arguments);
        if (authority.isEmpty()) {
            throw new SecurityException("CONTENT_PROVIDER_AUTHORITY_UNRESOLVED");
        }
        // Settings is a platform provider, not a guest-owned provider.  It remains behind the
        // SettingsProviderIdentityHook, which projects Android ID and virtual settings values;
        // allowing the transport here only lets Settings acquire its system IContentProvider.
        if ("settings".equals(authority)) return Interception.passThrough();
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
        if (closed) return;
        // Publish the terminal state before closing any provider.  Shutdown callbacks may re-enter
        // framework code; they must observe this interceptor as dead and cannot allocate a holder.
        closed = true;
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
                context, spec, descriptor.packageName, descriptor.virtualUserId,
                descriptor.processName, descriptor.authority, descriptor.componentClass,
                descriptor.exported);
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
        info.packageName = descriptor.packageName;
        info.name = descriptor.componentClass;
        info.authority = descriptor.authority;
        info.exported = descriptor.exported;
        info.enabled = true;
        info.processName = descriptor.processName;
        info.readPermission = descriptor.readPermission;
        info.writePermission = descriptor.writePermission;
        info.grantUriPermissions = descriptor.grantUriPermissions;
        PathPermissionProjection.apply(info, descriptor.pathRules);
        info.applicationInfo = descriptor.applicationInfo == null
                ? new ApplicationInfo(context.getApplicationInfo())
                : new ApplicationInfo(descriptor.applicationInfo);
        info.applicationInfo.packageName = descriptor.packageName;
        if (descriptor.packageName.equals(spec.packageName)) {
            try {
                GuestManifestMetadata metadata = GuestManifestMetadata.read(context.getAssets());
                info.metaData = metadata.provider(descriptor.authority);
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                throw new IllegalStateException("GUEST_PROVIDER_METADATA_READ_FAILED", error);
            }
        }
        return info;
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

    private record ProviderDescriptor(String packageName, int virtualUserId, String authority,
                                      String componentClass, boolean exported, String processName,
                                      String readPermission, String writePermission,
                                      boolean grantUriPermissions,
                                      java.util.List<VirtualProviderPathRuleSnapshot> pathRules,
                                      ApplicationInfo applicationInfo) { }

    /** Projects ProviderInfo path/URI grant rules without depending on hidden field visibility. */
    private static final class PathPermissionProjection {
        private PathPermissionProjection() { }

        static void apply(ProviderInfo info, java.util.List<VirtualProviderPathRuleSnapshot> rules) {
            if (rules == null || rules.isEmpty()) return;
            java.util.ArrayList<android.content.pm.PathPermission> permissions = new java.util.ArrayList<>();
            java.util.ArrayList<android.os.PatternMatcher> uriPatterns = new java.util.ArrayList<>();
            for (VirtualProviderPathRuleSnapshot rule : rules) {
                if (rule == null) continue;
                int kind = rule.path().isEmpty()
                        ? (rule.pathPrefix().isEmpty()
                        ? android.os.PatternMatcher.PATTERN_SIMPLE_GLOB
                        : android.os.PatternMatcher.PATTERN_PREFIX)
                        : android.os.PatternMatcher.PATTERN_LITERAL;
                String pattern = rule.path().isEmpty()
                        ? (rule.pathPrefix().isEmpty() ? rule.pathPattern() : rule.pathPrefix())
                        : rule.path();
                if (rule.uriGrantRule()) uriPatterns.add(new android.os.PatternMatcher(pattern, kind));
                else permissions.add(new android.content.pm.PathPermission(pattern, kind,
                        rule.readPermission(), rule.writePermission()));
            }
            if (!permissions.isEmpty()) info.pathPermissions = permissions.toArray(
                    new android.content.pm.PathPermission[0]);
            if (!uriPatterns.isEmpty()) {
                try {
                    setField(info, "uriPermissionPatterns",
                            uriPatterns.toArray(new android.os.PatternMatcher[0]), false);
                } catch (IllegalAccessException error) {
                    throw new IllegalStateException("CONTENT_PROVIDER_URI_PATTERN_PROJECTION_FAILED",
                            error);
                }
            }
        }
    }
}
