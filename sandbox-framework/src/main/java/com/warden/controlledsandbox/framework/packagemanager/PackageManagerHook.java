package com.warden.controlledsandbox.framework.packagemanager;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import android.content.Context;
import android.content.pm.PackageManager;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/** Process-local IPackageManager identity view. Installed only inside dedicated Guest processes. */
public final class PackageManagerHook implements AutoCloseable {
    private final Object packageManager;
    private final Field field;
    private final Object original;

    private PackageManagerHook(Object packageManager, Field field, Object original) {
        this.packageManager = packageManager;
        this.field = field;
        this.original = original;
    }

    public static PackageManagerHook install(Context context, GuestIdentity identity) throws Exception {
        return install(context.getPackageManager(), identity);
    }

    public static PackageManagerHook install(
            PackageManager packageManager, GuestIdentity identity) throws Exception {
        if (packageManager == null) throw new IllegalArgumentException("packageManager is required");
        Field mPm = findField(packageManager.getClass(), "mPM");
        mPm.setAccessible(true);
        Object original = mPm.get(packageManager);
        if (original == null) throw new IllegalStateException("ApplicationPackageManager.mPM is null");
        Class<?>[] interfaces = original.getClass().getInterfaces();
        if (interfaces.length == 0) throw new IllegalStateException("IPackageManager proxy exposes no interfaces");
        InvocationHandler handler = new PackageManagerInvocationHandler(original, identity);
        Object proxy = Proxy.newProxyInstance(original.getClass().getClassLoader(), interfaces, handler);
        mPm.set(packageManager, proxy);
        return new PackageManagerHook(packageManager, mPm, original);
    }

    @Override public void close() {
        try { field.set(packageManager, original); } catch (Throwable ignored) { }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

}
