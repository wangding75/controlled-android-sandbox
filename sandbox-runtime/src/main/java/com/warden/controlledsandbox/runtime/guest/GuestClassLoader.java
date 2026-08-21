package com.warden.controlledsandbox.runtime.guest;

import android.annotation.SuppressLint;
import android.os.Build;
import com.warden.controlledsandbox.contract.VirtualDetectionPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.nativebridge.NativePolicy;
import dalvik.system.InMemoryDexClassLoader;
import dalvik.system.PathClassLoader;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.nio.ByteBuffer;
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
    private final ClassLoader dex;
    /** Direct buffers must remain strongly reachable for the lifetime of InMemoryDexClassLoader. */
    private final List<ByteBuffer> dexBuffers;
    private final java.lang.reflect.Method dexFindClass;
    private final java.lang.reflect.Method dexFindLoaded;
    private final java.lang.reflect.Method dexFindLibrary;
    private final java.lang.reflect.Method dexFindResource;
    private final java.lang.reflect.Method dexFindResources;
    private volatile boolean translatedGuestAbi;

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
        this(new PathClassLoader(dexPath == null ? "" : dexPath, librarySearchPath, parent),
                parent, guestPackageName, declaredGuestClasses, List.of());
    }

    /**
     * FD-backed Guest loader used by platform isolated processes.  Android's DexFile path APIs
     * require a pathname that the isolated SELinux domain cannot traverse; InMemoryDexClassLoader
     * keeps the same BaseDexClassLoader/NativeLoader boundary while accepting validated APK
     * bytes obtained from the Binder capability.
     */
    GuestClassLoader(List<ByteBuffer> dexBuffers, String librarySearchPath,
                     ClassLoader parent, String guestPackageName,
                     List<String> declaredGuestClasses) {
        this(newInMemoryDexClassLoader(requireBuffers(dexBuffers), librarySearchPath, parent),
                parent, guestPackageName, declaredGuestClasses, requireBuffers(dexBuffers));
    }

    private GuestClassLoader(ClassLoader dex, ClassLoader parent, String guestPackageName,
                             List<String> declaredGuestClasses, List<ByteBuffer> dexBuffers) {
        super(parent);
        this.guestPackageName = normalizePackageName(guestPackageName);
        this.declaredGuestNamespaces = guestNamespaces(declaredGuestClasses);
        this.dex = dex;
        this.dexBuffers = List.copyOf(dexBuffers);
        this.dexFindClass = requireClassLoaderMethod("findClass", String.class);
        this.dexFindLoaded = requireClassLoaderMethod("findLoadedClass", String.class);
        this.dexFindLibrary = requireClassLoaderMethod("findLibrary", String.class);
        this.dexFindResource = requireClassLoaderMethod("findResource", String.class);
        this.dexFindResources = requireClassLoaderMethod("findResources", String.class);
    }

    @SuppressLint("NewApi")
    private static ClassLoader newInMemoryDexClassLoader(List<ByteBuffer> dexBuffers,
                                                         String librarySearchPath,
                                                         ClassLoader parent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IllegalStateException("FD_BACKED_GUEST_DEX_REQUIRES_API_29");
        }
        return new InMemoryDexClassLoader(dexBuffers.toArray(new ByteBuffer[0]),
                librarySearchPath == null ? "" : librarySearchPath, parent);
    }

    private static List<ByteBuffer> requireBuffers(List<ByteBuffer> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("FD-backed Guest dex buffers are required");
        }
        ArrayList<ByteBuffer> result = new ArrayList<>(values.size());
        for (ByteBuffer value : values) {
            if (value == null || !value.isDirect() || !value.hasRemaining()) {
                throw new IllegalArgumentException("FD-backed Guest dex buffer is invalid");
            }
            result.add(value.asReadOnlyBuffer());
        }
        return List.copyOf(result);
    }

    /** Platform loader that actually defines Guest classes and owns the NativeLoader namespace. */
    public ClassLoader definingLoader() { return dex; }

    /**
     * Load a Guest-defined component class from the defining dex loader only.
     * A miss must not fall back to the host process ClassLoader.
     */
    public Class<?> loadDefinedClass(String name) throws ClassNotFoundException {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("class name is required");
        }
        String className = name.trim();
        synchronized (this) {
            Class<?> loaded = findLoadedClass(className);
            if (loaded == null) loaded = dexFindLoaded(className);
            if (loaded != null) return loaded;
            if (dexFindClass == null) {
                throw new ClassNotFoundException(
                        "GUEST_DEFINING_LOADER_MISS:" + className + ":"
                                + dex.getClass().getName() + ":findClass-inaccessible");
            }
            try {
                return dexFindClass(className);
            } catch (ClassNotFoundException missing) {
                throw new ClassNotFoundException(
                        "GUEST_DEFINING_LOADER_MISS:" + className + ":"
                                + dex.getClass().getName(),
                        missing);
            }
        }
    }

    /**
     * Foreign-ABI code is executed by Android's native bridge. Host-ABI PLT patching and
     * Camera1 symbol replacement cannot safely cross that bridge, so translated guests use the
     * Java/framework camera route and leave platform native tables untouched.
     */
    void configureNativeCompatibility(boolean translatedGuestAbi) {
        this.translatedGuestAbi = translatedGuestAbi;
    }

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
            if ("android.hardware.Camera".equals(name) && !translatedGuestAbi) {
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

    /**
     * Resources are part of the Guest class-loader namespace just like classes and native
     * libraries.  ClassLoader's default implementation only asks the parent first, which would
     * make a Host copy of META-INF/services or a library configuration file shadow the Guest APK.
     * Keep the Guest defining loader first and use the Host parent only as a fallback.
     */
    @Override public URL getResource(String name) {
        if (name == null || name.isEmpty()) return null;
        synchronized (this) {
            URL guest = dexFindResource(name);
            if (guest != null) return guest;
            ClassLoader parent = getParent();
            return parent == null ? null : parent.getResource(name);
        }
    }

    @Override public Enumeration<URL> getResources(String name) throws IOException {
        if (name == null || name.isEmpty()) return Collections.emptyEnumeration();
        synchronized (this) {
            ArrayList<URL> result = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            appendResources(result, seen, dexFindResources(name));
            ClassLoader parent = getParent();
            if (parent != null) appendResources(result, seen, parent.getResources(name));
            return Collections.enumeration(result);
        }
    }

    @Override protected URL findResource(String name) {
        return dexFindResource(name);
    }

    @Override protected Enumeration<URL> findResources(String name) throws IOException {
        return dexFindResources(name);
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

    private URL dexFindResource(String name) {
        if (dexFindResource == null) return dex.getResource(name);
        try {
            Object value = dexFindResource.invoke(dex, name);
            return value instanceof URL ? (URL) value : null;
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            return null;
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Enumeration<URL> dexFindResources(String name) throws IOException {
        if (dexFindResources == null) return dex.getResources(name);
        try {
            Object value = dexFindResources.invoke(dex, name);
            return value instanceof Enumeration<?> ? (Enumeration<URL>) value
                    : Collections.emptyEnumeration();
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IOException("Guest resource lookup failed", cause);
        } catch (ReflectiveOperationException error) {
            throw new IOException("Guest resource lookup failed", error);
        }
    }

    private static void appendResources(List<URL> result, Set<String> seen,
                                        Enumeration<URL> resources) {
        if (resources == null) return;
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            if (resource == null) continue;
            String key = resource.toExternalForm();
            if (seen.add(key)) result.add(resource);
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
