package com.warden.controlledsandbox.runtime.guest;

import dalvik.system.DexClassLoader;

/** Child-first loader for Guest code with strict parent-first namespaces for platform and sandbox APIs. */
public final class GuestClassLoader extends DexClassLoader {
    GuestClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath,
                     ClassLoader parent) {
        super(dexPath, optimizedDirectory, librarySearchPath, parent);
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (isParentFirst(name)) {
                    loaded = getParent().loadClass(name);
                } else {
                    try { loaded = findClass(name); }
                    catch (ClassNotFoundException guestMiss) { loaded = getParent().loadClass(name); }
                }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    static boolean isParentFirst(String name) {
        if (name == null) return true;
        return name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("android.") || name.startsWith("androidx.")
                || name.startsWith("kotlin.") || name.startsWith("dalvik.")
                || name.startsWith("sun.") || name.startsWith("com.android.")
                || name.startsWith("com.warden.controlledsandbox.contract.")
                || name.startsWith("com.warden.controlledsandbox.framework.")
                || name.startsWith("com.warden.controlledsandbox.runtime.")
                || name.startsWith("com.warden.controlledsandbox.nativebridge.");
    }
}
