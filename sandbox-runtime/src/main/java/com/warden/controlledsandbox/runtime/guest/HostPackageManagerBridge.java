package com.warden.controlledsandbox.runtime.guest;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Narrow raw-host PMS bridge used only for owner classification.
 *
 * <p>FrameworkHooks replaces the process-local ApplicationPackageManager transport with the
 * virtual PMS adapter.  Keeping the original hidden IPackageManager object captured before that
 * replacement lets Guest routing compare the virtual result with the real host owner without
 * re-entering the virtual PackageManager or exposing arbitrary host packages.</p>
 */
final class HostPackageManagerBridge {
    private HostPackageManagerBridge() { }

    enum LookupStatus {
        RESULT,
        EMPTY,
        UNSUPPORTED_SIGNATURE,
        INVOCATION_FAILURE,
        UNEXPECTED_RETURN
    }

    /**
     * A raw PMS lookup outcome.  Only {@link LookupStatus#EMPTY} is the normal platform
     * "not found" result.  Every other non-result status is an adapter defect or unsupported
     * platform shape and must not be silently converted into a missing component.
     */
    static final class Lookup<T> {
        private final LookupStatus status;
        private final T value;
        private final String signature;
        private final String detail;

        private Lookup(LookupStatus status, T value, String signature, String detail) {
            this.status = status;
            this.value = value;
            this.signature = signature;
            this.detail = detail;
        }

        static <T> Lookup<T> result(T value, String signature) {
            return new Lookup<>(LookupStatus.RESULT, value, signature, "");
        }

        static <T> Lookup<T> empty(String signature) {
            return new Lookup<>(LookupStatus.EMPTY, null, signature, "");
        }

        static <T> Lookup<T> failed(LookupStatus status, String signature, String detail) {
            return new Lookup<>(status, null, signature, detail == null ? "" : detail);
        }

        LookupStatus status() { return status; }
        T value() { return value; }
        boolean isResult() { return status == LookupStatus.RESULT; }
        boolean isEmpty() { return status == LookupStatus.EMPTY; }
        String diagnostic() {
            return "status=" + status + " signature=" + signature + " detail=" + detail;
        }
    }

    private record MethodSignature(String name, Class<?>... parameterTypes) {
        String display() {
            StringBuilder text = new StringBuilder(name).append('(');
            for (int index = 0; index < parameterTypes.length; index++) {
                if (index > 0) text.append(',');
                text.append(parameterTypes[index].getSimpleName());
            }
            return text.append(')').toString();
        }
    }

    // These are the API32+ IPackageManager Binder contracts audited in P1-07. Do not add an
    // overload by name alone: an OEM-specific method must be captured as a P2-07 first fault.
    private static final MethodSignature RESOLVE_SERVICE_LONG = new MethodSignature("resolveService",
            Intent.class, String.class, long.class, int.class);
    private static final MethodSignature RESOLVE_SERVICE_INT = new MethodSignature("resolveService",
            Intent.class, String.class, int.class, int.class);
    private static final MethodSignature QUERY_INTENT_SERVICES_LONG = new MethodSignature(
            "queryIntentServices", Intent.class, String.class, long.class, int.class);
    private static final MethodSignature QUERY_INTENT_SERVICES_INT = new MethodSignature(
            "queryIntentServices", Intent.class, String.class, int.class, int.class);
    private static final MethodSignature RESOLVE_CONTENT_PROVIDER_LONG = new MethodSignature(
            "resolveContentProvider", String.class, long.class, int.class);
    private static final MethodSignature RESOLVE_CONTENT_PROVIDER_INT = new MethodSignature(
            "resolveContentProvider", String.class, int.class, int.class);

    static Object capture(PackageManager packageManager) {
        if (packageManager == null) return null;
        try {
            Field field = findField(packageManager.getClass(), "mPM");
            field.setAccessible(true);
            Object service = field.get(packageManager);
            return service;
        } catch (Throwable unavailable) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                    .rethrowIfFatal(unavailable);
            android.util.Log.w("CS_HOST_PMS", "raw package-manager transport unavailable",
                    unavailable);
            return null;
        }
    }

    static Lookup<ServiceInfo> resolveService(Object packageManagerService, Intent intent,
                                              int userId) {
        // PackageManager passes Intent.resolveTypeIfNeeded(), which is null for the service
        // contracts used here.  An empty string is a different MIME type to PMS and causes a
        // valid package-constrained filter to resolve to null on Android 14+/OEM PMS builds.
        Lookup<Object> resolved = invokeSupported(packageManagerService,
                new Invocation(RESOLVE_SERVICE_LONG, intent, null, 0L, userId),
                new Invocation(RESOLVE_SERVICE_INT, intent, null, 0, userId));
        if (resolved.isResult()) {
            if (resolved.value() instanceof ResolveInfo resolveInfo
                    && resolveInfo.serviceInfo != null) {
                return Lookup.result(resolveInfo.serviceInfo, resolved.signature);
            }
            if (resolved.value() instanceof ServiceInfo serviceInfo) {
                return Lookup.result(serviceInfo, resolved.signature);
            }
            return Lookup.failed(LookupStatus.UNEXPECTED_RETURN, resolved.signature,
                    "expected ResolveInfo/ServiceInfo but was "
                            + resolved.value().getClass().getName());
        }
        if (!resolved.isEmpty()) return copyFailure(resolved);
        // A few OEM PMS implementations return null from resolveService() for a package-
        // constrained binder-interface action even though queryIntentServices() exposes the
        // exact exported ServiceInfo. Preserve PMS filtering by taking the first already-ranked
        // query result, rather than inventing a component from a package dump.
        Lookup<Object> queried = invokeSupported(packageManagerService,
                new Invocation(QUERY_INTENT_SERVICES_LONG, intent, null, 0L, userId),
                new Invocation(QUERY_INTENT_SERVICES_INT, intent, null, 0, userId));
        Lookup<List<Object>> queryValues = listValues(queried);
        if (!queryValues.isResult()) {
            return queryValues.isEmpty() ? Lookup.empty(queried.signature) : copyFailure(queryValues);
        }
        for (Object value : queryValues.value()) {
            if (value instanceof ResolveInfo resolveInfo && resolveInfo.serviceInfo != null) {
                return Lookup.result(resolveInfo.serviceInfo, queried.signature);
            }
        }
        return Lookup.empty(queried.signature);
    }

    static Lookup<ProviderInfo> resolveContentProvider(Object packageManagerService,
                                                        String authority, long flags, int userId) {
        Lookup<Object> resolved = invoke(new Invocation(RESOLVE_CONTENT_PROVIDER_LONG,
                authority, flags, userId), packageManagerService);
        if (resolved.status == LookupStatus.UNSUPPORTED_SIGNATURE) {
            if (flags < Integer.MIN_VALUE || flags > Integer.MAX_VALUE) {
                return Lookup.failed(LookupStatus.UNSUPPORTED_SIGNATURE,
                        RESOLVE_CONTENT_PROVIDER_LONG.display() + " | "
                                + RESOLVE_CONTENT_PROVIDER_INT.display(),
                        "API32 int flags cannot represent 0x" + Long.toHexString(flags));
            }
            resolved = invoke(new Invocation(RESOLVE_CONTENT_PROVIDER_INT, authority,
                    (int) flags, userId), packageManagerService);
        }
        if (resolved.isEmpty()) return Lookup.empty(resolved.signature);
        if (!resolved.isResult()) return copyFailure(resolved);
        if (resolved.value() instanceof ProviderInfo providerInfo) {
            return Lookup.result(providerInfo, resolved.signature);
        }
        return Lookup.failed(LookupStatus.UNEXPECTED_RETURN, resolved.signature,
                "expected ProviderInfo but was " + resolved.value().getClass().getName());
    }

    static boolean isSystemOwner(ApplicationInfo applicationInfo) {
        if (applicationInfo == null) return false;
        int systemFlags = ApplicationInfo.FLAG_SYSTEM | UPDATED_SYSTEM_APP_FLAG;
        return (applicationInfo.flags & systemFlags) != 0;
    }

    // Android's ApplicationInfo.FLAG_UPDATED_SYSTEM_APP is 0x80.  The static Android source
    // stubs intentionally expose only the stable subset of ApplicationInfo constants.
    private static final int UPDATED_SYSTEM_APP_FLAG = 0x80;

    static int physicalUserId() {
        try {
            Method method = Class.forName("android.os.UserHandle").getMethod("myUserId");
            Object value = method.invoke(null);
            return value instanceof Integer integer ? integer : 0;
        } catch (Throwable unavailable) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                    .rethrowIfFatal(unavailable);
            return 0;
        }
    }

    private record Invocation(MethodSignature signature, Object... values) { }

    private static Lookup<Object> invokeSupported(Object target, Invocation... candidates) {
        Lookup<Object> lastMissing = null;
        for (Invocation candidate : candidates) {
            Lookup<Object> result = invoke(candidate, target);
            if (result.status != LookupStatus.UNSUPPORTED_SIGNATURE) return result;
            lastMissing = result;
        }
        String supported = Arrays.stream(candidates).map(item -> item.signature.display())
                .collect(java.util.stream.Collectors.joining(" | "));
        return Lookup.failed(LookupStatus.UNSUPPORTED_SIGNATURE, supported,
                lastMissing == null ? "no supported signatures" : lastMissing.detail);
    }

    private static Lookup<Object> invoke(Invocation invocation, Object target) {
        MethodSignature signature = invocation.signature;
        if (target == null) {
            return Lookup.failed(LookupStatus.UNSUPPORTED_SIGNATURE, signature.display(),
                    "raw PMS transport unavailable");
        }
        try {
            Method selected = findExactMethod(target.getClass(), signature);
            if (selected == null) {
                String detail = "class=" + target.getClass().getName();
                android.util.Log.w("CS_HOST_PMS", "method missing " + signature.display()
                        + ' ' + detail);
                return Lookup.failed(LookupStatus.UNSUPPORTED_SIGNATURE, signature.display(), detail);
            }
            selected.setAccessible(true);
            Object result = selected.invoke(target, invocation.values);
            String selectedSignature = methodDisplay(selected);
            android.util.Log.i("CS_HOST_PMS", "selected=" + selectedSignature
                    + " target=" + target.getClass().getName());
            return result == null ? Lookup.empty(selectedSignature)
                    : Lookup.result(result, selectedSignature);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            android.util.Log.w("CS_HOST_PMS", "raw PMS query failed " + signature.display(),
                    cause);
            return Lookup.failed(LookupStatus.INVOCATION_FAILURE, signature.display(),
                    cause.getClass().getName() + ':' + value(cause.getMessage()));
        } catch (Throwable unavailable) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                    .rethrowIfFatal(unavailable);
            android.util.Log.w("CS_HOST_PMS", "raw PMS query unavailable " + signature.display(),
                    unavailable);
            return Lookup.failed(LookupStatus.INVOCATION_FAILURE, signature.display(),
                    unavailable.getClass().getName() + ':' + value(unavailable.getMessage()));
        }
    }

    private static Method findExactMethod(Class<?> type, MethodSignature signature) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(signature.name)
                    && Arrays.equals(method.getParameterTypes(), signature.parameterTypes)) {
                return method;
            }
        }
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getName().equals(signature.name)
                        && Arrays.equals(method.getParameterTypes(), signature.parameterTypes)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static String methodDisplay(Method method) {
        return new MethodSignature(method.getName(), method.getParameterTypes()).display()
                + "->" + method.getReturnType().getSimpleName();
    }

    private static <T> Lookup<T> copyFailure(Lookup<?> source) {
        return Lookup.failed(source.status, source.signature, source.detail);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Lookup<List<Object>> listValues(Lookup<Object> source) {
        if (source.isEmpty()) return Lookup.empty(source.signature);
        if (!source.isResult()) return copyFailure(source);
        Object value = source.value();
        if (value instanceof List<?> list) {
            return Lookup.result(new java.util.ArrayList<>(list), source.signature);
        }
        try {
            Method method = value.getClass().getMethod("getList");
            Object list = method.invoke(value);
            if (list == null) return Lookup.result(Collections.emptyList(), source.signature);
            if (list instanceof List<?> values) {
                return Lookup.result(new java.util.ArrayList<>(values), source.signature);
            }
        } catch (Throwable unavailable) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                    .rethrowIfFatal(unavailable);
            return Lookup.failed(LookupStatus.UNEXPECTED_RETURN, source.signature,
                    "getList failed: " + unavailable.getClass().getName());
        }
        return Lookup.failed(LookupStatus.UNEXPECTED_RETURN, source.signature,
                "expected List or getList() result but was " + value.getClass().getName());
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }
}
