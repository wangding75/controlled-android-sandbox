package com.warden.controlledsandbox.runtime.guest;

import android.app.Application;
import android.content.pm.ApplicationInfo;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Publishes the Guest's actual LoadedApk projection into ActivityThread.
 *
 * <p>The Guest Application is still created through the controlled bootstrap because the host
 * package is not the Guest APK. Once created, however, every framework lookup in the process must
 * converge on one LoadedApk carrying the Guest ApplicationInfo, ClassLoader, resources,
 * AppComponentFactory and Application. The bridge records and restores the exact host entries on
 * generation teardown.</p>
 */
final class GuestLoadedApkBridge implements AutoCloseable {
    private final Object activityThread;
    private final Object boundApplication;
    private final Field boundInfoField;
    private final Object originalBoundInfo;
    private final Field packagesField;
    private final Object packages;
    private final Object originalPackageEntry;
    private final Object loadedApk;
    private final String packageName;
    private final boolean installed;
    private boolean closed;

    private GuestLoadedApkBridge(Object activityThread, Object boundApplication,
                                  Field boundInfoField, Object originalBoundInfo,
                                  Field packagesField, Object packages,
                                  Object originalPackageEntry, Object loadedApk,
                                  String packageName) {
        this.activityThread = activityThread;
        this.boundApplication = boundApplication;
        this.boundInfoField = boundInfoField;
        this.originalBoundInfo = originalBoundInfo;
        this.packagesField = packagesField;
        this.packages = packages;
        this.originalPackageEntry = originalPackageEntry;
        this.loadedApk = loadedApk;
        this.packageName = packageName;
        this.installed = true;
    }

    static GuestLoadedApkBridge install(GuestRuntimeEnvironment.Session session) throws Exception {
        Class<?> activityThreadType = Class.forName("android.app.ActivityThread");
        Method current = activityThreadType.getDeclaredMethod("currentActivityThread");
        current.setAccessible(true);
        Object activityThread = current.invoke(null);
        if (activityThread == null) throw new IllegalStateException("GUEST_ACTIVITY_THREAD_UNAVAILABLE");

        Field boundField = findField(activityThreadType, "mBoundApplication");
        boundField.setAccessible(true);
        Object boundApplication = boundField.get(activityThread);
        if (boundApplication == null) throw new IllegalStateException("GUEST_BOUND_APPLICATION_UNAVAILABLE");
        Field boundInfoField = findField(boundApplication.getClass(), "info");
        boundInfoField.setAccessible(true);
        Object originalBoundInfo = boundInfoField.get(boundApplication);
        if (originalBoundInfo == null) throw new IllegalStateException("HOST_LOADED_APK_UNAVAILABLE");

        Method compatibilityMethod = originalBoundInfo.getClass().getDeclaredMethod(
                "getCompatibilityInfo");
        compatibilityMethod.setAccessible(true);
        Object compatibility = compatibilityMethod.invoke(originalBoundInfo);
        Class<?> compatibilityType = Class.forName("android.content.res.CompatibilityInfo");
        Class<?> loadedApkType = Class.forName("android.app.LoadedApk");
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Constructor<?> constructor = loadedApkType.getConstructor(activityThreadClass,
                ApplicationInfo.class, compatibilityType, ClassLoader.class,
                boolean.class, boolean.class, boolean.class);
        ApplicationInfo guestInfo = new ApplicationInfo(session.context.getApplicationInfo());
        ClassLoader processLoader = session.context.getClassLoader();
        // LoadedApk is also the NativeLoader namespace owner. When the controlled wrapper is
        // used as the lookup facade, publish its real PathClassLoader to framework code so
        // translated native libraries keep the platform loader/bridge association.
        if (processLoader instanceof GuestClassLoader guestClassLoader) {
            processLoader = guestClassLoader.definingLoader();
        }
        Object loadedApk = constructor.newInstance(activityThread, guestInfo, compatibility,
                processLoader, false, true, false);
        setOptional(loadedApk, "mClassLoader", processLoader);
        setOptional(loadedApk, "mDefaultClassLoader", processLoader);
        setOptional(loadedApk, "mResources", session.resources.resources);
        // handleBindApplication creates/publishes LoadedApk before the Application object is
        // constructed.  Do not force a second Application through LoadedApk.makeApplication at
        // this point; bindApplication(Application) completes the same framework-owned state
        // transition after AppComponentFactory has selected the Guest class.

        Field packagesField = findField(activityThreadType, "mPackages");
        packagesField.setAccessible(true);
        Object packages = packagesField.get(activityThread);
        Object originalEntry = mapGet(packages, session.spec.packageName);
        mapPut(packages, session.spec.packageName, new WeakReference<>(loadedApk));
        boundInfoField.set(boundApplication, loadedApk);
        android.util.Log.i("CS_GUEST_LOADED_APK", "installed package=" + session.spec.packageName
                + " loader=" + processLoader.getClass().getName()
                + " application=DEFERRED_UNTIL_BIND_APPLICATION");
        return new GuestLoadedApkBridge(activityThread, boundApplication, boundInfoField,
                originalBoundInfo, packagesField, packages, originalEntry, loadedApk,
                session.spec.packageName);
    }

    synchronized void bindApplication(Application application) {
        if (closed) throw new IllegalStateException("GUEST_LOADED_APK_ALREADY_CLOSED");
        if (application == null) throw new IllegalArgumentException("application is required");
        Object current = readOptional(loadedApk, "mApplication");
        if (current != null && current != application) {
            throw new IllegalStateException("GUEST_LOADED_APK_APPLICATION_SPLIT_BRAIN");
        }
        setOptional(loadedApk, "mApplication", application);
        verifyFrameworkApplicationOwnership(loadedApk, activityThread, application);
        android.util.Log.i("CS_GUEST_LOADED_APK", "frameworkMakeApplication=OWNED_BY_GUEST"
                + " application=" + application.getClass().getName());
    }

    Object loadedApk() { return loadedApk; }

    /**
     * Force the platform LoadedApk API to observe the already-created Guest Application.  The
     * host process cannot safely let LoadedApk instantiate a second Application (its ContextImpl
     * would be host-owned), so the controlled bootstrap creates and attaches the Guest object;
     * this call then makes the framework's own makeApplication lookup the same object and catches
     * a split-brain Application early.
     */
    private static void verifyFrameworkApplicationOwnership(Object loadedApk,
                                                             Object activityThread,
                                                             Application expected) {
        try {
            Method make = findMethod(loadedApk.getClass(), "makeApplication", boolean.class,
                    Class.forName("android.app.Instrumentation"));
            if (make == null) return;
            Field instrumentationField = findField(activityThread.getClass(), "mInstrumentation");
            instrumentationField.setAccessible(true);
            Object instrumentation = instrumentationField.get(activityThread);
            Object actual = make.invoke(loadedApk, false, instrumentation);
            if (actual != expected) {
                throw new IllegalStateException("GUEST_LOADED_APK_APPLICATION_SPLIT_BRAIN");
            }
            android.util.Log.i("CS_GUEST_LOADED_APK", "frameworkMakeApplication=OWNED_BY_GUEST");
        } catch (ClassNotFoundException ignored) {
            // Impossible on a normal Android runtime; leave the projection usable on test stubs.
        } catch (NoSuchFieldException ignored) {
            // ActivityThread instrumentation field shape varies across preview/OEM builds.
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            throw new IllegalStateException("GUEST_LOADED_APK_MAKE_APPLICATION_FAILED", cause);
        } catch (ReflectiveOperationException error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("GUEST_LOADED_APK_MAKE_APPLICATION_FAILED", error);
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (boundInfoField.get(boundApplication) == loadedApk) {
                boundInfoField.set(boundApplication, originalBoundInfo);
            }
            if (mapGet(packages, packageName) instanceof WeakReference
                    && ((WeakReference<?>) mapGet(packages, packageName)).get() == loadedApk) {
                if (originalPackageEntry == null) mapRemove(packages, packageName);
                else mapPut(packages, packageName, originalPackageEntry);
            }
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_GUEST_LOADED_APK", "restore failed", error);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                Method method = cursor.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    private static void setOptional(Object target, String name, Object value) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException ignored) {
            // Field shapes vary across supported Android releases.
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("GUEST_LOADED_APK_FIELD_FAILED:" + name, error);
        }
    }

    private static Object readOptional(Object target, String name) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            return field.get(target);
        } catch (NoSuchFieldException ignored) {
            return null;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("GUEST_LOADED_APK_FIELD_READ_FAILED:" + name, error);
        }
    }

    private static Object mapGet(Object map, Object key) throws Exception {
        Method method = map.getClass().getMethod("get", Object.class);
        method.setAccessible(true);
        return method.invoke(map, key);
    }

    private static void mapPut(Object map, Object key, Object value) throws Exception {
        Method method = map.getClass().getMethod("put", Object.class, Object.class);
        method.setAccessible(true);
        method.invoke(map, key, value);
    }

    private static void mapRemove(Object map, Object key) throws Exception {
        Method method = map.getClass().getMethod("remove", Object.class);
        method.setAccessible(true);
        method.invoke(map, key);
    }
}
