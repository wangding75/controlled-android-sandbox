package com.warden.controlledsandbox.framework.service;
import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.Window;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.util.ArrayList;
import java.util.List;
/** Reversible AutofillManager Binder replacement. */
public final class AutofillManagerServiceHook {
    private AutofillManagerServiceHook() { }

    /**
     * Bind both sides of the AOSP Autofill boundary.  A DecorView can retain an
     * AutofillManager created from the framework Activity base context before the Guest
     * Activity fields are projected, so replacing only one manager instance is insufficient.
     * NBB/VA inject the ServiceManager Binder and then replace the manager's cached mService;
     * the descriptor-checked helper preserves that same order and rollback contract.
     */
    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        android.util.Log.i("CS_AUTOFILL_PROXY", "BINDING_BEGIN context="
                + context.getClass().getName() + " manager=" + managerDescription(context));
        AutoCloseable hook = ReflectiveServiceHook.managerFieldCandidatesOrServiceManagerBinding(
                context, "autofill", "autofill",
                "android.view.autofill.IAutoFillManager", identity,
                "mService", "sService");
        android.util.Log.i("CS_AUTOFILL_PROXY", "BINDING_END context="
                + context.getClass().getName() + " manager=" + managerDescription(context));
        return hook;
    }

    private static String managerDescription(Context context) {
        try {
            Object manager = context.getSystemService("autofill");
            if (manager == null) return "null";
            java.lang.reflect.Field field = null;
            Class<?> cursor = manager.getClass();
            while (cursor != null && field == null) {
                try { field = cursor.getDeclaredField("mService"); }
                catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
            }
            if (field == null) return manager.getClass().getName() + ":mService-missing";
            field.setAccessible(true);
            Object service = field.get(manager);
            String binder = "";
            if (service instanceof android.os.IInterface iface && iface.asBinder() != null) {
                binder = iface.asBinder().getClass().getName() + ":" +
                        iface.asBinder().getInterfaceDescriptor();
            }
            return manager.getClass().getName() + ":mService="
                    + (service == null ? "null" : service.getClass().getName())
                    + ":binder=" + binder;
        } catch (Throwable error) {
            return "diagnostic-error=" + error.getClass().getSimpleName();
        }
    }

    /**
     * Rebind managers owned by a real Guest Activity's framework objects. Activity.attach()
     * creates the Window with the physical Stub Context before the Guest Activity base Context is
     * projected; AOSP ViewRootImpl consequently resolves AutofillManager from that Window/DecorView
     * Context. VA and NBB both repair this per-manager cache after ServiceManager injection.
     */
    public static AutoCloseable installForActivity(Activity activity, GuestIdentity identity)
            throws Exception {
        if (activity == null) throw new IllegalArgumentException("activity is required");
        if (identity == null) throw new IllegalArgumentException("identity is required");
        List<AutoCloseable> hooks = new ArrayList<>();
        try {
            hooks.add(install(activity, identity));
            Window window = activity.getWindow();
            if (window != null) {
                Context windowContext = contextOf(window);
                if (windowContext != null && windowContext != activity) {
                    hooks.add(install(windowContext, identity));
                }
                View decor = existingDecor(activity);
                Context decorContext = contextOf(decor);
                if (decorContext != null && decorContext != activity
                        && decorContext != windowContext) {
                    hooks.add(install(decorContext, identity));
                }
            }
            return ReflectiveServiceHook.compose(hooks.toArray(new AutoCloseable[0]));
        } catch (Throwable error) {
            for (int index = hooks.size() - 1; index >= 0; index--) {
                try { hooks.get(index).close(); } catch (Throwable rollback) {
                    error.addSuppressed(rollback);
                }
            }
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("AUTOFILL_ACTIVITY_BINDING_FAILED", error);
        }
    }

    private static View existingDecor(Activity activity) {
        Class<?> cursor = activity.getClass();
        while (cursor != null) {
            try {
                java.lang.reflect.Field field = cursor.getDeclaredField("mDecor");
                field.setAccessible(true);
                Object value = field.get(activity);
                return value instanceof View view ? view : null;
            } catch (NoSuchFieldException missing) {
                cursor = cursor.getSuperclass();
            } catch (Throwable error) {
                return null;
            }
        }
        return null;
    }

    private static Context contextOf(Object owner) {
        if (owner == null) return null;
        try {
            java.lang.reflect.Method method = owner.getClass().getMethod("getContext");
            method.setAccessible(true);
            Object value = method.invoke(owner);
            return value instanceof Context context ? context : null;
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                    .rethrowIfFatal(error);
            return null;
        }
    }
}
