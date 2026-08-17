package com.warden.controlledsandbox.runtime.guest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Single defining-loader contract for Activity, Service, Receiver and Provider.
 *
 * <p>Guest classes are defined by the LoadedApk/PathClassLoader world published during
 * bindApplication. Component instantiation must use that loader and must not fall back to the
 * host process DexPathList after a guest miss.
 */
public final class GuestDefiningLoader {
    private GuestDefiningLoader() { }

    public static ClassLoader of(GuestContext context) {
        if (context == null) throw new IllegalArgumentException("guest context is required");
        return unwrap(context.getClassLoader());
    }

    public static ClassLoader of(GuestRuntimeEnvironment.Session session) {
        if (session == null) throw new IllegalArgumentException("session is required");
        if (session.context() != null && session.context().getClassLoader() != null) {
            return unwrap(session.context().getClassLoader());
        }
        return unwrap(session.classLoader());
    }

    public static ClassLoader unwrap(ClassLoader loader) {
        if (loader == null) throw new IllegalStateException("GUEST_DEFINING_LOADER_MISSING");
        if (loader instanceof GuestClassLoader guest) {
            ClassLoader defining = guest.definingLoader();
            if (defining == null) throw new IllegalStateException("GUEST_DEFINING_LOADER_MISSING");
            return defining;
        }
        return loader;
    }

    public static Class<?> loadComponent(ClassLoader loader, String className)
            throws ClassNotFoundException {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("component class is required");
        }
        String name = className.trim();
        if (loader instanceof GuestClassLoader guest) {
            return guest.loadDefinedClass(name);
        }
        return findDefinedClass(unwrap(loader), name);
    }

    public static Class<?> loadComponent(GuestRuntimeEnvironment.Session session, String className)
            throws ClassNotFoundException {
        if (session == null) throw new IllegalArgumentException("session is required");
        if (session.classLoader() != null) {
            return session.classLoader().loadDefinedClass(className);
        }
        return loadComponent(of(session), className);
    }

    static Class<?> findDefinedClass(ClassLoader defining, String className)
            throws ClassNotFoundException {
        if (defining == null) throw new IllegalStateException("GUEST_DEFINING_LOADER_MISSING");
        if (defining instanceof GuestClassLoader guest) {
            return guest.loadDefinedClass(className);
        }
        try {
            Method findClass = ClassLoader.class.getDeclaredMethod("findClass", String.class);
            try {
                findClass.setAccessible(true);
            } catch (RuntimeException inaccessible) {
                return loadFromDefining(defining, className);
            }
            Object loaded = findClass.invoke(defining, className);
            if (loaded instanceof Class<?> type) return type;
            throw miss(className, defining, null);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof ClassNotFoundException missing) {
                throw miss(className, defining, missing);
            }
            throw miss(className, defining, cause);
        } catch (ReflectiveOperationException | SecurityException inaccessible) {
            return loadFromDefining(defining, className);
        }
    }

    private static Class<?> loadFromDefining(ClassLoader defining, String className)
            throws ClassNotFoundException {
        try {
            return defining.loadClass(className);
        } catch (ClassNotFoundException missing) {
            throw miss(className, defining, missing);
        }
    }

    private static ClassNotFoundException miss(String className, ClassLoader defining,
                                               Throwable cause) {
        String identity = defining == null ? "null" : defining.getClass().getName();
        return new ClassNotFoundException(
                "GUEST_DEFINING_LOADER_MISS:" + className + ":" + identity, cause);
    }
}
