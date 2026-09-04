package com.warden.controlledsandbox.framework.permission;

import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.core.GuestSystemServiceOverrideRegistry;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

public final class AppOpsManagerHook {
    private AppOpsManagerHook() { }
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        return install(context, context, identity);
    }

    public static AutoCloseable install(Context guestContext, Context hostServiceContext,
                                        GuestIdentity identity) throws Exception {
        AutoCloseable serviceHook = ReflectiveServiceHook.managerField(
                hostServiceContext, "appops", "mService", identity);
        AutoCloseable staticServiceHook = null;
        try {
            // API37 keeps AppOpsManager's IAppOpsService field on the manager instance, but the
            // ActivityThread path can materialize a second manager from its Activity Context.
            // Reusing only the Host application's manager therefore leaves that second instance
            // pointed at the Host Binder. Construct the framework manager with the already
            // projected IAppOpsService and expose it through the Guest-only service registry.
            Object hostManager = hostServiceContext.getSystemService("appops");
            Field serviceField = findField(hostManager.getClass(), "mService");
            serviceField.setAccessible(true);
            Object projectedService = serviceField.get(hostManager);
            if (projectedService == null) {
                throw new IllegalStateException("APPOPS_PROJECTED_SERVICE_UNAVAILABLE");
            }
            Class<?> managerType = Class.forName("android.app.AppOpsManager");
            Class<?> serviceType = Class.forName("com.android.internal.app.IAppOpsService");
            Constructor<?> constructor = managerType.getDeclaredConstructor(
                    Context.class, serviceType);
            constructor.setAccessible(true);
            // API37's constructor eagerly asks its context for a PackageManager before the
            // mandatory hook report is sealed. Keep Guest identity for op-package/attribution
            // calls while routing that bootstrap-only lookup to the already-hooked Host context.
            Context managerContext = new ContextWrapper(guestContext) {
                @Override public PackageManager getPackageManager() {
                    return hostServiceContext.getPackageManager();
                }
            };
            Object guestManager = constructor.newInstance(managerContext, projectedService);
            // API37's checkPackage() takes the new IpcDataCache path, whose QueryHandler reads
            // AppOpsManager.sService rather than the per-manager mService field. Project both
            // framework entry points so cached and uncached calls share the same Guest Binder.
            staticServiceHook = ReflectiveServiceHook.staticField(
                    "android.app.AppOpsManager", "sService", "getService", identity, "appops");
            AutoCloseable override = GuestSystemServiceOverrideRegistry.install(
                    guestContext, "appops", guestManager);
            try {
                Object observed = guestContext.getSystemService(managerType);
                if (observed != guestManager) {
                    override.close();
                    throw new IllegalStateException("APPOPS_MANAGER_GUEST_LOOKUP_MISMATCH");
                }
                android.util.Log.i("CS_APPOPS_PROXY", "API37 manager=GUEST_CONTEXT_OVERRIDE"
                        + " service=PROJECTED_IAppOpsService static=PROJECTED_IAppOpsService");
                return new CompositeHook(override, staticServiceHook, serviceHook);
            } catch (Throwable error) {
                try { override.close(); } catch (Exception rollback) { error.addSuppressed(rollback); }
                if (staticServiceHook != null) {
                    try { staticServiceHook.close(); } catch (Exception rollback) {
                        error.addSuppressed(rollback);
                    }
                }
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                        .rethrowIfFatal(error);
                if (error instanceof Exception exception) throw exception;
                if (error instanceof Error fatal) throw fatal;
                throw new IllegalStateException("APPOPS_MANAGER_GUEST_LOOKUP_FAILED", error);
            }
        } catch (Throwable error) {
            if (staticServiceHook != null) {
                try { staticServiceHook.close(); } catch (Exception rollback) {
                    error.addSuppressed(rollback);
                }
            }
            try { serviceHook.close(); } catch (Exception rollback) { error.addSuppressed(rollback); }
            Throwable cause = error instanceof InvocationTargetException target
                    && target.getCause() != null ? target.getCause() : error;
            android.util.Log.e("CS_APPOPS_PROXY", "GUEST_MANAGER_CONSTRUCTION_FAILED type="
                    + cause.getClass().getName() + " message=" + cause.getMessage(), cause);
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                    .rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("APPOPS_MANAGER_GUEST_INSTALL_FAILED", error);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static final class CompositeHook implements AutoCloseable {
        private final AutoCloseable first;
        private final AutoCloseable second;
        private final AutoCloseable third;

        private CompositeHook(AutoCloseable first, AutoCloseable second, AutoCloseable third) {
            this.first = first;
            this.second = second;
            this.third = third;
        }

        @Override public void close() throws Exception {
            Exception failure = null;
            try { first.close(); } catch (Exception error) { failure = error; }
            try { second.close(); } catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            try { third.close(); } catch (Exception error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }
}
