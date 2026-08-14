package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualDetectionPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.nativebridge.NativePolicy;
import dalvik.system.PathClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Policy wrapper around a platform {@link PathClassLoader}.
 *
 * <p>Guest classes are defined by the inner {@code PathClassLoader} so Android's NativeLoader
 * can associate one stable namespace with a loader type it recognizes. This wrapper only
 * applies host-internal denial and detection policy; it does not {@code defineClass} Guest
 * types. Native library search paths are fixed on the inner loader at construction.
 *
 * <p>Not parallel-capable; uses {@code synchronized (this)}.
 */
public final class GuestClassLoader extends ClassLoader {
    private static final String SANDBOX_ROOT = "com.warden.controlledsandbox.";
    private static final List<String> HOST_INTERNAL_PREFIXES = List.of(
            SANDBOX_ROOT + "runtime.",
            SANDBOX_ROOT + "framework.",
            SANDBOX_ROOT + "nativebridge.",
            SANDBOX_ROOT + "domain.",
            SANDBOX_ROOT + "companion32.");

    private volatile List<String> hiddenClassPrefixes = List.of();
    private volatile int maximumSuspiciousQueries;
    private final AtomicInteger suspiciousQueries = new AtomicInteger();
    private final String guestPackageName;
    private final List<String> declaredGuestNamespaces;
    private final PathClassLoader dex;
    private final java.lang.reflect.Method dexFindClass;
    private final java.lang.reflect.Method dexFindLoaded;
    private final java.lang.reflect.Method dexFindLibrary;

    GuestClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath,
                     ClassLoader parent) {
        this(dexPath, optimizedDirectory, librarySearchPath, parent, "");
    }

    GuestClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath,
                     ClassLoader parent, String guestPackageName) {
        this(dexPath, optimizedDirectory, librarySearchPath, parent, guestPackageName, List.of());
    }

    GuestClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath,
                     ClassLoader parent, String guestPackageName,
                     List<String> declaredGuestClasses) {
        super(parent);
        this.guestPackageName = normalizePackageName(guestPackageName);
        this.declaredGuestNamespaces = guestNamespaces(declaredGuestClasses);
        this.dex = new PathClassLoader(dexPath == null ? "" : dexPath, librarySearchPath, parent);
        this.dexFindClass = requireClassLoaderMethod("findClass", String.class);
        this.dexFindLoaded = requireClassLoaderMethod("findLoadedClass", String.class);
        this.dexFindLibrary = requireClassLoaderMethod("findLibrary", String.class);
    }

    /** Platform loader that actually defines Guest classes and owns the NativeLoader namespace. */
    public ClassLoader definingLoader() { return dex; }

    void configureDetection(VirtualDetectionPolicySnapshot policy) {
        if (policy == null || VirtualLocationProfileSnapshot.MODE_HOST.equals(policy.mode())) {
            hiddenClassPrefixes = List.of();
            maximumSuspiciousQueries = 0;
            suspiciousQueries.set(0);
            return;
        }
        hiddenClassPrefixes = List.copyOf(policy.hiddenClassPrefixes());
        maximumSuspiciousQueries = policy.maximumSuspiciousQueries();
        suspiciousQueries.set(0);
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (this) {
            if (isDeniedSandboxInternal(name) || isPrivilegedContract(name)) {
                throw new ClassNotFoundException("Sandbox privileged implementation is not a Guest API: " + name);
            }
            if (isPolicyHidden(name)) {
                throw new ClassNotFoundException("Class is hidden by Guest detection policy: " + name);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) loaded = dexFindLoaded(name);
            if (loaded == null) {
                ClassLoader parent = getParent();
                if (isParentFirst(name)) {
                    try {
                        loaded = parent == null ? Class.forName(name, false, null) : parent.loadClass(name);
                    } catch (ClassNotFoundException hostMiss) {
                        loaded = dexFindClass(name);
                    }
                } else {
                    try {
                        loaded = dexFindClass(name);
                    } catch (ClassNotFoundException guestMiss) {
                        if (parent == null) throw guestMiss;
                        loaded = parent.loadClass(name);
                    }
                }
            }
            if (resolve && loaded.getClassLoader() == this) resolveClass(loaded);
            if ("android.hardware.Camera".equals(name)) {
                boolean camera1Installed = NativePolicy.installCamera1Adapter();
                android.util.Log.i("CS_CAMERA1_NATIVE", "CAMERA_CLASS_LOADED adapterInstalled="
                        + camera1Installed + " status=" + NativePolicy.camera1Status());
            }
            return loaded;
        }
    }

    private boolean isPolicyHidden(String name) throws ClassNotFoundException {
        if (name == null || isParentFirst(name)) return false;
        // The default profile hides the Host package namespace. A Guest APK may intentionally use
        // that namespace, so exempt only its package id or manifest-declared class namespaces
        // after the Host-internal boundary check in loadClass().
        if (belongsToGuestPackage(name) || belongsToDeclaredGuestNamespace(name)) return false;
        for (String prefix : hiddenClassPrefixes) {
            if (!name.startsWith(prefix)) continue;
            int count = suspiciousQueries.incrementAndGet();
            if (maximumSuspiciousQueries > 0 && count > maximumSuspiciousQueries) {
                throw new ClassNotFoundException("Virtual-environment query quota exceeded");
            }
            return true;
        }
        return false;
    }

    private boolean belongsToGuestPackage(String name) {
        return !guestPackageName.isEmpty()
                && (name.equals(guestPackageName) || name.startsWith(guestPackageName + "."));
    }

    private boolean belongsToDeclaredGuestNamespace(String name) {
        for (String namespace : declaredGuestNamespaces) {
            if (name.equals(namespace) || name.startsWith(namespace + ".")) return true;
        }
        return false;
    }

    private static List<String> guestNamespaces(List<String> declaredGuestClasses) {
        if (declaredGuestClasses == null || declaredGuestClasses.isEmpty()) return List.of();
        Set<String> namespaces = new LinkedHashSet<>();
        for (String className : declaredGuestClasses) {
            String normalized = normalizePackageName(className);
            int separator = normalized.lastIndexOf('.');
            if (separator <= 0 || separator == normalized.length() - 1) continue;
            namespaces.add(normalized.substring(0, separator));
            if (namespaces.size() > 1024) {
                throw new IllegalArgumentException("Guest class namespace list is too large");
            }
        }
        return List.copyOf(new ArrayList<>(namespaces));
    }

    @Override public String findLibrary(String name) {
        String resolved = dexFindLibrary(name);
        GuestNativeBindingDiagnostic.recordLibraryLookup(dex, name, resolved);
        return resolved;
    }

    private Class<?> dexFindClass(String name) throws ClassNotFoundException {
        if (dexFindClass == null) return dex.loadClass(name);
        try {
            return (Class<?>) dexFindClass.invoke(dex, name);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ClassNotFoundException missing) throw missing;
            throw new ClassNotFoundException(name, cause);
        } catch (ReflectiveOperationException error) {
            throw new ClassNotFoundException(name, error);
        }
    }

    private Class<?> dexFindLoaded(String name) {
        if (dexFindLoaded == null) return null;
        try {
            return (Class<?>) dexFindLoaded.invoke(dex, name);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private String dexFindLibrary(String name) {
        if (dexFindLibrary == null) return null;
        try {
            Object value = dexFindLibrary.invoke(dex, name);
            return value instanceof String path ? path : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static java.lang.reflect.Method requireClassLoaderMethod(String name, Class<?>... types) {
        try {
            java.lang.reflect.Method method = ClassLoader.class.getDeclaredMethod(name, types);
            try {
                method.setAccessible(true);
            } catch (RuntimeException inaccessible) {
                // The host self-test runs on a strongly encapsulated JDK. Android's hidden-api
                // bridge makes these methods accessible in the real Guest process; a null
                // adapter uses the platform loader's public loadClass fallback for tests.
                return null;
            }
            return method;
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException("CLASS_LOADER_METHOD_UNAVAILABLE:" + name, error);
        }
    }

    int suspiciousQueryCount() { return suspiciousQueries.get(); }

    static boolean isDeniedSandboxInternal(String name) {
        if (name == null || !name.startsWith(SANDBOX_ROOT)) return false;
        if (name.startsWith(SANDBOX_ROOT + "contract.")) return false;
        for (String prefix : HOST_INTERNAL_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        String relativeName = name.substring(SANDBOX_ROOT.length());
        return relativeName.indexOf('.') < 0;
    }

    static boolean isPrivilegedContract(String name) {
        if (name == null) return false;
        return name.startsWith("com.warden.controlledsandbox.contract.internal.")
                || name.equals("com.warden.controlledsandbox.contract.IPackageAuthorityBootstrap")
                || name.startsWith("com.warden.controlledsandbox.contract.IPackageAuthorityBootstrap$")
                || name.equals("com.warden.controlledsandbox.contract.IPackageService")
                || name.startsWith("com.warden.controlledsandbox.contract.IPackageService$")
                || name.equals("com.warden.controlledsandbox.contract.IPackageManagementSession")
                || name.startsWith("com.warden.controlledsandbox.contract.IPackageManagementSession$")
                || name.equals("com.warden.controlledsandbox.contract.IRuntimePermissionSession")
                || name.startsWith("com.warden.controlledsandbox.contract.IRuntimePermissionSession$")
                || name.equals("com.warden.controlledsandbox.contract.IVirtualSystemServiceSession")
                || name.startsWith("com.warden.controlledsandbox.contract.IVirtualSystemServiceSession$")
                || name.equals("com.warden.controlledsandbox.contract.IHostJobCallback")
                || name.startsWith("com.warden.controlledsandbox.contract.IHostJobCallback$")
                || name.equals("com.warden.controlledsandbox.contract.PackageAuthorityCapabilityContract");
    }

    static boolean isParentFirst(String name) {
        if (name == null) return true;
        return name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("android.") || name.startsWith("androidx.")
                || name.startsWith("dalvik.")
                || name.startsWith("sun.") || name.startsWith("com.android.")
                || name.startsWith("com.warden.controlledsandbox.contract.");
    }

    private static String normalizePackageName(String value) {
        return value == null ? "" : value.trim();
    }
}
