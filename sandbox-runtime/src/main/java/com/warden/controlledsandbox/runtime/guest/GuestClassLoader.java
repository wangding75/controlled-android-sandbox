package com.warden.controlledsandbox.runtime.guest;

import android.annotation.SuppressLint;
import android.os.Build;
import com.warden.controlledsandbox.contract.VirtualDetectionPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.nativebridge.NativePolicy;
import dalvik.system.InMemoryDexClassLoader;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
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
 * types. Native library search paths are initialized on the inner loader at construction;
 * ordinary Guests may project the retained path metadata after construction.
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
        this(newSharedPathClassLoader(dexPath, librarySearchPath, parent),
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
        String lib = withSystemRendererSearchPath(librarySearchPath);
        InMemoryDexClassLoader created = new InMemoryDexClassLoader(
                dexBuffers.toArray(new ByteBuffer[0]), lib, parent);
        shareLinkerNamespace(created, "", lib);
        return created;
    }

    /**
     * NBB/VA load guest dex on the process ClassLoader, so libhwui is already in that
     * nativeloader namespace. A raw {@link PathClassLoader} gets an isolated namespace whose
     * permitted paths are only {@code /data:/mnt/expand}; {@code /system/lib64/libhwui.so} is
     * then inaccessible and Chromium/U4 DrawFunctor SIGSEGVs. ClassLoaderFactory is the
     * platform constructor that creates a shared namespace with /system permitted.
     */
    private static ClassLoader newSharedPathClassLoader(String dexPath, String librarySearchPath,
                                                        ClassLoader parent) {
        String dex = dexPath == null ? "" : dexPath;
        String lib = withSystemRendererSearchPath(librarySearchPath);
        ClassLoader created = createSharedClassLoader(dex, lib, parent);
        if (created != null) return created;
        PathClassLoader fallback = new PathClassLoader(dex, lib, parent);
        shareLinkerNamespace(fallback, dex, lib);
        return fallback;
    }

    private static final String SYSTEM_RENDERER_PERMITTED_PATHS =
            "/system:/system_ext:/apex:/vendor:/product:/odm:/data:/mnt/expand";

    private static String withSystemRendererSearchPath(String librarySearchPath) {
        boolean sixtyFour = false;
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null) {
            for (String abi : abis) {
                if (abi != null && abi.contains("64")) {
                    sixtyFour = true;
                    break;
                }
            }
        }
        String suffix = sixtyFour ? "lib64" : "lib";
        String extra = "/system/" + suffix
                + ":/system_ext/" + suffix
                + ":/vendor/" + suffix
                + ":/product/" + suffix
                + ":/apex/com.android.i18n/" + suffix
                + ":/apex/com.android.runtime/" + suffix
                + ":/apex/com.android.art/" + suffix
                + ":/apex/com.android.media/" + suffix
                + ":/apex/com.android.media.swcodec/" + suffix
                + ":/apex/com.android.os.statsd/" + suffix;
        String current = librarySearchPath == null ? "" : librarySearchPath;
        if (current.isEmpty()) return extra;
        if (current.contains("/system/" + suffix)) return current;
        return current + ":" + extra;
    }

    private static ClassLoader createSharedClassLoader(String dexPath, String librarySearchPath,
                                                       ClassLoader parent) {
        try {
            Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
            int sdk = Build.VERSION.SDK_INT;
            String loaderName = PathClassLoader.class.getName();
            for (java.lang.reflect.Method method : factory.getDeclaredMethods()) {
                if (!"createClassLoader".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length < 7 || types[0] != String.class || types[1] != String.class
                        || types[2] != String.class || types[3] != ClassLoader.class
                        || types[4] != int.class || types[5] != boolean.class
                        || types[6] != String.class) {
                    continue;
                }
                method.setAccessible(true);
                Object[] args = new Object[types.length];
                args[0] = dexPath;
                args[1] = librarySearchPath;
                args[2] = SYSTEM_RENDERER_PERMITTED_PATHS;
                args[3] = parent;
                args[4] = sdk;
                args[5] = Boolean.TRUE;
                args[6] = loaderName;
                for (int i = 7; i < types.length; i++) {
                    if (types[i] == List.class) {
                        // nativeSharedLibraries is the String list after classLoaderName;
                        // ClassLoader lists stay null.
                        args[i] = (i == 8) ? List.of("libhwui.so") : null;
                    } else if (types[i] == String.class) args[i] = "";
                    else if (types[i] == boolean.class) args[i] = Boolean.FALSE;
                    else args[i] = null;
                }
                Object created = method.invoke(null, args);
                if (created instanceof ClassLoader loader) {
                    android.util.Log.i("CS_NATIVE_BIND",
                            "CLASSLOADER_NS shared=true permitted=" + SYSTEM_RENDERER_PERMITTED_PATHS
                                    + " loader=" + loader.getClass().getName());
                    return loader;
                }
            }
        } catch (Throwable error) {
            android.util.Log.w("CS_NATIVE_BIND", "CLASSLOADER_NS factory failed: "
                    + error.getClass().getName() + ":" + error.getMessage());
        }
        return null;
    }

    private static void shareLinkerNamespace(ClassLoader loader, String dexPath,
                                             String librarySearchPath) {
        if (loader == null) return;
        try {
            Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
            java.lang.reflect.Method method = factory.getDeclaredMethod(
                    "createClassloaderNamespace", ClassLoader.class, int.class, String.class,
                    String.class, boolean.class, String.class, String.class);
            method.setAccessible(true);
            Object error = method.invoke(null, loader, Build.VERSION.SDK_INT, librarySearchPath,
                    SYSTEM_RENDERER_PERMITTED_PATHS, Boolean.TRUE, dexPath, "libhwui.so");
            android.util.Log.i("CS_NATIVE_BIND", "CLASSLOADER_NS native shared=true error="
                    + (error == null ? "ok" : error));
        } catch (Throwable error) {
            android.util.Log.w("CS_NATIVE_BIND", "CLASSLOADER_NS native failed: "
                    + error.getClass().getName() + ":" + error.getMessage());
        }
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
     * Projects the paths retained by the defining loader after ART has opened the Guest DEX.
     *
     * <p>The loader must be constructed with the verified CAS paths: on Android the
     * {@code PathClassLoader} constructor opens the DEX before CAS's IO hooks are installed.
     * Once construction has completed, the bytes and native-loader namespace are already bound,
     * while the path strings retained by {@code DexPathList} remain observable through U4's
     * ClassLoader inspection.  Rewrite those retained path objects, including ART's
     * {@code DexFile.mFileName}, to their Guest-visible aliases.  The dex file is already open;
     * this changes the path metadata U4 reads, not the bytes or the NativeLoader namespace.
     * This is deliberately best effort because ART's private field layout varies by release;
     * failure is logged and does not turn a valid Guest class loader into a bootstrap failure.
     *
     * @param realApkPath verified CAS base APK path
     * @param logicalApkPath Guest-visible base APK alias
     * @param realNativeRoot selected or packaged CAS native root
     * @param logicalNativeRoot Guest-visible native library alias used by the metadata facade
     * @param realGuestDataRoot CAS Guest instance data root, used for runtime overlays
     * @param logicalGuestDataRoot Guest-visible data root for runtime overlays
     */
    void projectDexPathListPaths(String realApkPath, String logicalApkPath,
                                 String realNativeRoot, String logicalNativeRoot,
                                 String realGuestDataRoot, String logicalGuestDataRoot) {
        List<PathMapping> mappings = new ArrayList<>();
        addMapping(mappings, realApkPath, logicalApkPath);
        addMapping(mappings, realNativeRoot, logicalNativeRoot);
        addMapping(mappings, packageRoot(realApkPath),
                guestPackageName.isEmpty() ? "" : "/data/app/" + guestPackageName);
        addMapping(mappings, realGuestDataRoot, logicalGuestDataRoot);
        mappings.sort((left, right) -> Integer.compare(right.real.length(), left.real.length()));
        if (mappings.isEmpty()) {
            android.util.Log.i("CS_CLASSLOADER", "DEX_PATH_PROJECTION skipped reason=no-mapping");
            return;
        }

        ProjectionStats stats = new ProjectionStats();
        try {
            Field pathListField = findField(dex.getClass(), "pathList");
            Object pathList = pathListField.get(dex);
            if (pathList == null) {
                android.util.Log.w("CS_CLASSLOADER", "DEX_PATH_PROJECTION skipped reason=null-path-list");
                return;
            }
            try {
                projectDexElements(pathList, mappings, stats);
            } catch (ReflectiveOperationException | RuntimeException error) {
                stats.failures++;
            }
            // Keep the defining loader's native search entries physical.  Android's
            // BaseDexClassLoader.findLibrary() resolves System.loadLibrary() by checking these
            // entries before Runtime.nativeLoad() is called.  A logical /data/app path does not
            // exist on the device, so projecting these fields makes a library such as sgmain
            // fail before NativePolicy can translate an absolute load argument.  VA/NBB keep a
            // loadable search root and virtualize file access at the native boundary; CAS does
            // the same here.  ApplicationInfo.nativeLibraryDir and explicit absolute loads are
            // still projected/translated independently.
            android.util.Log.i("CS_CLASSLOADER", "DEX_PATH_PROJECTION applied=true"
                    + " dexElements=" + stats.dexElements
                    + " dexFileNames=" + stats.dexFileNames
                    + " nativeDirs=0"
                    + " nativeElements=0"
                    + " nativeSearch=physical"
                    + " failures=" + stats.failures);
        } catch (ReflectiveOperationException | RuntimeException error) {
            android.util.Log.w("CS_CLASSLOADER", "DEX_PATH_PROJECTION applied=false error="
                    + error.getClass().getName() + ":" + safeMessage(error), error);
        }
    }

    /** Compatibility overload for callers that do not create runtime overlays. */
    void projectDexPathListPaths(String realApkPath, String logicalApkPath,
                                 String realNativeRoot, String logicalNativeRoot) {
        projectDexPathListPaths(realApkPath, logicalApkPath, realNativeRoot, logicalNativeRoot,
                "", "");
    }

    private static void projectDexElements(Object pathList, List<PathMapping> mappings,
                                           ProjectionStats stats)
            throws ReflectiveOperationException {
        Field elementsField = findOptionalField(pathList.getClass(), "dexElements");
        if (elementsField == null) return;
        Object value = elementsField.get(pathList);
        if (!(value instanceof Object[] elements)) return;
        for (Object element : elements) {
            if (element == null) continue;
            Field pathField = findOptionalField(element.getClass(), "path");
            if (pathField != null) {
                try {
                    Object current = pathField.get(element);
                    String projected = projectFileOrString(current, mappings);
                    if (projected != null && !samePath(current, projected)) {
                        setFileOrString(pathField, element, current, projected);
                        stats.dexElements++;
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    stats.failures++;
                }
            }

            Field dexFileField = findOptionalField(element.getClass(), "dexFile");
            if (dexFileField != null) {
                try {
                    Object dexFile = dexFileField.get(element);
                    Field fileNameField = dexFile == null ? null
                            : findOptionalField(dexFile.getClass(), "mFileName");
                    if (fileNameField != null) {
                        Object current = fileNameField.get(dexFile);
                        String projected = projectFileOrString(current, mappings);
                        if (projected != null && !samePath(current, projected)) {
                            setFileOrString(fileNameField, dexFile, current, projected);
                            stats.dexFileNames++;
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    stats.failures++;
                }
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void projectFileList(Object pathList, String fieldName,
                                        List<PathMapping> mappings, ProjectionStats stats)
            throws ReflectiveOperationException {
        Field listField = findOptionalField(pathList.getClass(), fieldName);
        if (listField == null) return;
        Object value = listField.get(pathList);
        if (!(value instanceof List<?> list)) return;
        ArrayList<Object> projected = new ArrayList<>(list.size());
        boolean changed = false;
        for (Object current : list) {
            String projectedPath = projectFileOrString(current, mappings);
            if (projectedPath != null && !samePath(current, projectedPath)) {
                projected.add(projectedFileOrString(current, projectedPath));
                changed = true;
                stats.nativeDirectories++;
            } else {
                projected.add(current);
            }
        }
        if (!changed) return;
        try {
            List mutable = (List) list;
            for (int index = 0; index < projected.size(); index++) {
                mutable.set(index, projected.get(index));
            }
        } catch (RuntimeException unsupported) {
            // Some ART releases expose an immutable snapshot. Replacing the field is the
            // fallback; it may still fail for a final/hidden field and will be logged by the
            // outer best-effort boundary.
            listField.set(pathList, projected);
        }
    }

    private static void projectPathElements(Object pathList, List<PathMapping> mappings,
                                             ProjectionStats stats)
            throws ReflectiveOperationException {
        Field elementsField = findOptionalField(pathList.getClass(), "nativeLibraryPathElements");
        if (elementsField == null) return;
        Object value = elementsField.get(pathList);
        if (value instanceof List<?> elements) {
            for (Object element : elements) {
                projectPathElement(element, mappings, stats);
            }
            return;
        }
        if (value instanceof Object[] elements) {
            for (Object element : elements) {
                projectPathElement(element, mappings, stats);
            }
        }
    }

    private static void projectPathElement(Object element, List<PathMapping> mappings,
                                           ProjectionStats stats)
            throws ReflectiveOperationException {
        if (element == null) return;
        Field pathField = findOptionalField(element.getClass(), "path");
        if (pathField == null) pathField = findOptionalField(element.getClass(), "dir");
        if (pathField == null) return;
        try {
            Object current = pathField.get(element);
            String projected = projectFileOrString(current, mappings);
            if (projected != null && !samePath(current, projected)) {
                setFileOrString(pathField, element, current, projected);
                stats.nativePathElements++;
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            stats.failures++;
        }
    }

    private static String projectFileOrString(Object value, List<PathMapping> mappings) {
        if (value instanceof File file) return projectPath(file.getPath(), mappings);
        if (value instanceof String string) return projectPath(string, mappings);
        return null;
    }

    private static Object projectedFileOrString(Object original, String projected) {
        return original instanceof String ? projected : new File(projected);
    }

    private static boolean samePath(Object original, String projected) {
        if (original instanceof File file) return file.getPath().equals(projected);
        return original == null || original.equals(projected);
    }

    private static void setFileOrString(Field field, Object owner, Object original,
                                        String projected) throws IllegalAccessException {
        if (original instanceof File) field.set(owner, new File(projected));
        else if (original instanceof String) field.set(owner, projected);
    }

    private static String projectPath(String path, List<PathMapping> mappings) {
        if (path == null || path.isEmpty()) return path;
        String normalized = path.replace('\\', '/');
        for (PathMapping mapping : mappings) {
            if (normalized.equals(mapping.real)) return mapping.logical;
            if (normalized.startsWith(mapping.real + "/")) {
                return mapping.logical + normalized.substring(mapping.real.length());
            }
        }
        return path;
    }

    private static void addMapping(List<PathMapping> mappings, String real, String logical) {
        String normalizedReal = normalizePath(real);
        String normalizedLogical = normalizePath(logical);
        if (normalizedReal.isEmpty() || normalizedLogical.isEmpty()
                || normalizedReal.equals(normalizedLogical)) return;
        mappings.add(new PathMapping(normalizedReal, normalizedLogical));
    }

    private static String packageRoot(String realApkPath) {
        String normalized = normalizePath(realApkPath);
        if (normalized.isEmpty()) return "";
        int revisions = normalized.indexOf("/revisions/");
        if (revisions > 0) return normalized.substring(0, revisions);
        int slash = normalized.lastIndexOf('/');
        return slash > 0 ? normalized.substring(0, slash) : "";
    }

    private static String normalizePath(String value) {
        if (value == null) return "";
        String normalized = value.trim().replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException missing) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Field findOptionalField(Class<?> type, String name) {
        try {
            return findField(type, name);
        } catch (NoSuchFieldException | RuntimeException ignored) {
            return null;
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class PathMapping {
        final String real;
        final String logical;

        PathMapping(String real, String logical) {
            this.real = real;
            this.logical = logical;
        }
    }

    private static final class ProjectionStats {
        int dexElements;
        int dexFileNames;
        int nativeDirectories;
        int nativePathElements;
        int failures;
    }

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
