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

    static ServiceInfo resolveService(Object packageManagerService, Intent intent, int userId) {
        // PackageManager passes Intent.resolveTypeIfNeeded(), which is null for the service
        // contracts used here.  An empty string is a different MIME type to PMS and causes a
        // valid package-constrained filter to resolve to null on Android 14+/OEM PMS builds.
        Object result = invoke(packageManagerService, "resolveService", intent, null, 0L, userId);
        if (result instanceof ResolveInfo resolveInfo) return resolveInfo.serviceInfo;
        if (result instanceof ServiceInfo serviceInfo) return serviceInfo;
        // A few OEM PMS implementations return null from resolveService() for a package-
        // constrained binder-interface action even though queryIntentServices() exposes the
        // exact exported ServiceInfo. Preserve PMS filtering by taking the first already-ranked
        // query result, rather than inventing a component from a package dump.
        Object queried = invoke(packageManagerService, "queryIntentServices", intent, null, 0L,
                userId);
        List<Object> queryValues = listValues(queried);
        for (Object value : queryValues) {
            if (value instanceof ResolveInfo resolveInfo && resolveInfo.serviceInfo != null) {
                return resolveInfo.serviceInfo;
            }
        }
        return null;
    }

    static ProviderInfo resolveContentProvider(Object packageManagerService, String authority,
                                               long flags, int userId) {
        Object result = invoke(packageManagerService, "resolveContentProvider", authority, "",
                flags, userId);
        return result instanceof ProviderInfo providerInfo ? providerInfo : null;
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

    private static Object invoke(Object target, String name, Object first, String authority,
                                 long flags, int userId) {
        if (target == null) return null;
        try {
            Method selected = null;
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equals(name)) {
                    selected = method;
                    break;
                }
            }
            if (selected == null) {
                for (Class<?> type : target.getClass().getInterfaces()) {
                    for (Method method : type.getMethods()) {
                        if (method.getName().equals(name)) {
                            selected = method;
                            break;
                        }
                    }
                    if (selected != null) break;
                }
            }
            if (selected == null) {
                android.util.Log.w("CS_HOST_PMS", "method missing name=" + name
                        + " class=" + target.getClass().getName());
                return null;
            }
            selected.setAccessible(true);
            Class<?>[] types = selected.getParameterTypes();
            Object[] values = new Object[types.length];
            int integerIndex = 0;
            boolean hasLong = false;
            for (Class<?> type : types) if (type == long.class || type == Long.class) hasLong = true;
            for (int index = 0; index < types.length; index++) {
                Class<?> type = types[index];
                if (Intent.class.isAssignableFrom(type)) {
                    values[index] = first;
                } else if (type == String.class) {
                    values[index] = first instanceof String
                            ? first : authority;
                } else if (type == long.class || type == Long.class) {
                    values[index] = flags;
                } else if (type == int.class || type == Integer.class) {
                    // resolveService may carry an int-only flags overload; the user id is the
                    // final integer when a long flags parameter is present, otherwise the first
                    // integer is the legacy flags position.
                    values[index] = hasLong || integerIndex > 0 ? userId : (int) flags;
                    integerIndex++;
                } else if (type == boolean.class || type == Boolean.class) {
                    values[index] = false;
                } else {
                    return null;
                }
            }
            Object result = selected.invoke(target, values);
            return result;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            android.util.Log.w("CS_HOST_PMS", "raw PMS query failed method=" + name, cause);
            return null;
        } catch (Throwable unavailable) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                    .rethrowIfFatal(unavailable);
            android.util.Log.w("CS_HOST_PMS", "raw PMS query unavailable method=" + name,
                    unavailable);
            return null;
        }
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

    private static List<Object> listValues(Object value) {
        if (value == null) return Collections.emptyList();
        if (value instanceof List<?> list) return new java.util.ArrayList<>(list);
        try {
            Method method = value.getClass().getMethod("getList");
            Object list = method.invoke(value);
            if (list instanceof List<?> values) return new java.util.ArrayList<>(values);
        } catch (Throwable unavailable) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy
                    .rethrowIfFatal(unavailable);
        }
        return Collections.emptyList();
    }
}
