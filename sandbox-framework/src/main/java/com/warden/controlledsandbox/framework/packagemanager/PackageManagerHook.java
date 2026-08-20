package com.warden.controlledsandbox.framework.packagemanager;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import android.content.Context;
import android.content.pm.PackageManager;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Process-local IPackageManager identity view. Installed only inside dedicated Guest processes. */
public final class PackageManagerHook implements AutoCloseable {
    private final List<Binding> bindings;

    private PackageManagerHook(List<Binding> bindings) {
        this.bindings = Collections.unmodifiableList(new ArrayList<>(bindings));
    }

    public static PackageManagerHook install(Context context, GuestIdentity identity) throws Exception {
        return install(context.getPackageManager(), identity);
    }

    public static PackageManagerHook install(
            PackageManager packageManager, GuestIdentity identity) throws Exception {
        return install(packageManager, identity, new PackageManager[0]);
    }

    /**
     * Installs the same identity view on all process-local ApplicationPackageManager objects used
     * by the Guest Context and by framework code that reaches AppGlobals' initial Application.
     * Android may cache those objects independently; WebViewFactory uses the latter.
     */
    public static PackageManagerHook install(
            PackageManager packageManager, GuestIdentity identity, PackageManager... siblings)
            throws Exception {
        if (packageManager == null) throw new IllegalArgumentException("packageManager is required");
        List<Binding> installed = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        installOne(packageManager, identity, seen, installed);
        if (siblings != null) {
            for (PackageManager sibling : siblings) {
                if (sibling != null) installOne(sibling, identity, seen, installed);
            }
        }
        installActivityThreadSource(identity, installed);
        return new PackageManagerHook(installed);
    }

    @Override public void close() {
        for (int index = bindings.size() - 1; index >= 0; index--) {
            Binding binding = bindings.get(index);
            try { binding.handler.invalidateBinderBoundary("HOOK_CLOSED"); }
            catch (Throwable ignored) { }
            try { binding.field.set(binding.packageManager, binding.original); }
            catch (Throwable ignored) { }
        }
    }

    private static void installOne(PackageManager packageManager, GuestIdentity identity,
                                   Set<Object> seen, List<Binding> installed) throws Exception {
        if (!seen.add(packageManager)) return;
        Field mPm = findField(packageManager.getClass(), "mPM");
        mPm.setAccessible(true);
        Object original = mPm.get(packageManager);
        if (original == null) throw new IllegalStateException("ApplicationPackageManager.mPM is null");
        Class<?>[] interfaces = original.getClass().getInterfaces();
        if (interfaces.length == 0) throw new IllegalStateException("IPackageManager proxy exposes no interfaces");
        InvocationHandler handler = new PackageManagerInvocationHandler(original, identity);
        Object proxy = Proxy.newProxyInstance(original.getClass().getClassLoader(), interfaces, handler);
        ((PackageManagerInvocationHandler) handler).attachBinderBoundary(proxy);
        mPm.set(packageManager, proxy);
        android.util.Log.i("CS_PM_HOOK", "installed packageManager="
                + packageManager.getClass().getName() + "@"
                + System.identityHashCode(packageManager) + " original="
                + original.getClass().getName() + "@" + System.identityHashCode(original));
        installed.add(new Binding(packageManager, mPm, original,
                (PackageManagerInvocationHandler) handler));
    }

    /** ApplicationPackageManager instances created after bootstrap obtain this static source. */
    private static void installActivityThreadSource(GuestIdentity identity,
                                                     List<Binding> installed) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Field field = findField(activityThread, "sPackageManager");
            field.setAccessible(true);
            Object original = field.get(null);
            if (original == null) return;
            Class<?>[] interfaces = original.getClass().getInterfaces();
            if (interfaces.length == 0) return;
            InvocationHandler handler = new PackageManagerInvocationHandler(original, identity);
            Object proxy = Proxy.newProxyInstance(original.getClass().getClassLoader(), interfaces, handler);
            ((PackageManagerInvocationHandler) handler).attachBinderBoundary(proxy);
            field.set(null, proxy);
            android.util.Log.i("CS_PM_HOOK", "installed ActivityThread.sPackageManager source");
            installed.add(new Binding(null, field, original,
                    (PackageManagerInvocationHandler) handler));
        } catch (Throwable error) {
            android.util.Log.w("CS_PM_HOOK", "ActivityThread PackageManager source unavailable", error);
        }
    }

    private record Binding(Object packageManager, Field field, Object original,
                           PackageManagerInvocationHandler handler) { }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

}
