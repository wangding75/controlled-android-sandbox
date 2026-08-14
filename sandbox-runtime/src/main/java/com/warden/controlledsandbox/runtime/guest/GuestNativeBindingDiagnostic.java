package com.warden.controlledsandbox.runtime.guest;

import android.util.Log;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Observe-only native binding evidence. Records ClassLoader identity, library lookup,
 * and UnsatisfiedLinkError class ownership. Does not change ClassLoader parentage,
 * linker namespaces, or {@code System.loadLibrary} binding.
 */
public final class GuestNativeBindingDiagnostic {
    public static final String TAG = "CS_NATIVE_BIND";
    public static final String JNI_EX_TAG = "CS_JNI_EX";
    private static final int MAX_EVENTS = 48;
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static final List<ClassLoader> SEEN_LOADERS = new ArrayList<>();
    private static boolean probesInstalled;

    private GuestNativeBindingDiagnostic() { }

    public static synchronized void recordLoader(String site, ClassLoader loader) {
        rememberLoader(loader);
        emit("LOADER site=" + safe(site) + " " + describeLoader(loader));
    }

    public static synchronized void recordClass(String site, Class<?> type) {
        if (type != null) rememberLoader(type.getClassLoader());
        emit("CLASS site=" + safe(site) + " " + describeClass(type));
    }

    public static synchronized void recordLibraryLookup(ClassLoader loader, String name,
                                                       String resolved) {
        rememberLoader(loader);
        emit("LOOKUP name=" + safe(name)
                + " resolved=" + (resolved == null ? "null" : resolved)
                + " " + describeLoader(loader));
    }

    public static final String FAILURE_CLASS =
            "org.chromium.base.memory.DlmallocBlackBerryRepairor";

    public static synchronized void recordNativeLoad(String libraryName, Class<?> callerClass,
                                                    ClassLoader loader, String nativeLibraryPath) {
        rememberLoader(loader);
        if (callerClass != null) rememberLoader(callerClass.getClassLoader());
        String name = libraryName == null ? "" : libraryName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);
        emit("LOAD library=" + safe(name)
                + " callerClass=" + (callerClass == null ? "" : callerClass.getName())
                + " callerLoader=" + describeLoader(loader)
                + " path=" + safe(nativeLibraryPath));
        if (callerClass != null) {
            emit("LOAD_CALLER_DEFINING class=" + callerClass.getName()
                    + " " + describeLoader(callerClass.getClassLoader()));
        }
        if (nativeLibraryPath != null && nativeLibraryPath.contains("webviewuc")) {
            dumpMaps("libwebviewuc.so");
        }
        probeFailureClass();
    }

    public static synchronized void probeFailureClass() {
        probeNamedClass(FAILURE_CLASS);
    }

    public static synchronized void probeNamedClass(String className) {
        if (className == null || className.isEmpty()) return;
        boolean found = false;
        for (int index = SEEN_LOADERS.size() - 1; index >= 0; index--) {
            ClassLoader loader = SEEN_LOADERS.get(index);
            try {
                Class<?> type = Class.forName(className, false, loader);
                found = true;
                emit("FAILCLASS name=" + className
                        + " foundVia=" + describeLoader(loader)
                        + " defining=" + describeLoader(type.getClassLoader())
                        + " definingId=" + identity(type.getClassLoader())
                        + " sameAsProbe=" + (type.getClassLoader() == loader));
            } catch (Throwable ignored) {
                // Miss is evidence this loader does not define the class.
            }
        }
        if (!found) {
            try {
                Class<?> type = Class.forName(className, false, null);
                found = true;
                emit("FAILCLASS name=" + className + " foundVia=boot defining="
                        + describeLoader(type.getClassLoader())
                        + " definingId=" + identity(type.getClassLoader()));
            } catch (Throwable ignored) {
                emit("FAILCLASS name=" + className + " defining=unresolved seenLoaders="
                        + SEEN_LOADERS.size());
            }
        }
        if (found) dumpMaps("libwebviewuc.so");
    }

    public static synchronized void dumpMaps(String needle) {
        String match = needle == null ? "" : needle;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/maps"))) {
            int hits = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (match.isEmpty() || line.contains(match)) {
                    emit("MAPS pid=" + android.os.Process.myPid() + " " + line.trim());
                    hits++;
                    if (hits >= 12) break;
                }
            }
            if (hits == 0) {
                emit("MAPS pid=" + android.os.Process.myPid() + " needle=" + match + " hits=0");
            }
        } catch (Throwable error) {
            emit("MAPS pid=" + android.os.Process.myPid() + " error=" + error.getClass().getName()
                    + ":" + safe(String.valueOf(error.getMessage())));
        }
    }

    public static synchronized void recordPendingJniException(int tid, String exceptionClass,
                                                             String message, String javaStack,
                                                             String topJavaMethod,
                                                             String declaringClass,
                                                             String definingLoader,
                                                             String nativeSo) {
        String line = "PENDING tid=" + tid
                + " class=" + safe(exceptionClass)
                + " message=" + safe(message)
                + " top=" + safe(topJavaMethod)
                + " declaring=" + safe(declaringClass)
                + " defining=" + safe(definingLoader)
                + " so=" + safe(nativeSo)
                + " stack=" + (javaStack == null ? "" : javaStack.replace('\n', ' '));
        if (line.length() > 3000) line = line.substring(0, 3000);
        Log.e(JNI_EX_TAG, line);
        emit("JNI_EX " + line);
        if (declaringClass != null && !declaringClass.isEmpty()) {
            rememberLoaderFromName(declaringClass);
        }
    }

    public static synchronized String describeNamedClassLoader(String className) {
        return describeNamedClass(className == null ? "" : className);
    }

    public static synchronized void recordUnsatisfiedLink(Throwable error) {
        if (error == null) return;
        String message = String.valueOf(error.getMessage());
        String nativeClass = nativeClassName(message);
        emit("ULE type=" + error.getClass().getName() + " message=" + safe(message)
                + " nativeClass=" + nativeClass
                + " " + describeNamedClass(nativeClass));
    }

    public static synchronized void installProcessProbes() {
        if (probesInstalled) return;
        probesInstalled = true;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (containsUnsatisfiedLink(error)) recordUnsatisfiedLink(root(error));
            if (previous != null) previous.uncaughtException(thread, error);
        });
        emit("PROBE uncaughtLinkHandler=installed");
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable probe = GuestNativeBindingDiagnostic::probeFailureClass;
        handler.post(probe);
        handler.postDelayed(probe, 500L);
        handler.postDelayed(probe, 1500L);
        handler.postDelayed(probe, 3000L);
    }

    private static String identity(ClassLoader loader) {
        return loader == null ? "boot" : Integer.toHexString(System.identityHashCode(loader));
    }

    public static String describeLoader(ClassLoader loader) {
        if (loader == null) return "loader=boot";
        StringBuilder out = new StringBuilder();
        out.append("loader=").append(loader.getClass().getName())
                .append("@").append(Integer.toHexString(System.identityHashCode(loader)))
                .append(" guestLoader=").append(loader instanceof GuestClassLoader);
        out.append(" parents=");
        ClassLoader cursor = loader.getParent();
        int depth = 0;
        while (cursor != null && depth < 8) {
            if (depth > 0) out.append(">");
            out.append(cursor.getClass().getName())
                    .append("@")
                    .append(Integer.toHexString(System.identityHashCode(cursor)));
            cursor = cursor.getParent();
            depth++;
        }
        if (cursor != null) out.append(">…");
        if (cursor == null && depth == 0) out.append("boot");
        String nativeDirs = nativeLibraryDirectories(loader);
        if (!nativeDirs.isEmpty()) out.append(" nativeDirs=").append(nativeDirs);
        return out.toString();
    }

    public static String describeClass(Class<?> type) {
        if (type == null) return "class=null";
        return "class=" + type.getName() + " " + describeLoader(type.getClassLoader());
    }

    static synchronized List<String> snapshotEvents() {
        return List.copyOf(EVENTS);
    }

    static synchronized void resetForTest() {
        EVENTS.clear();
        SEEN_LOADERS.clear();
        probesInstalled = false;
    }

    private static void emit(String line) {
        String value = line == null ? "" : line.replace('\n', ' ').replace('\r', ' ');
        if (value.length() > 1024) value = value.substring(0, 1024);
        while (EVENTS.size() >= MAX_EVENTS) EVENTS.removeFirst();
        EVENTS.addLast(value);
        Log.i(TAG, value);
    }

    private static void rememberLoaderFromName(String className) {
        for (int index = SEEN_LOADERS.size() - 1; index >= 0; index--) {
            try {
                Class<?> type = Class.forName(className, false, SEEN_LOADERS.get(index));
                rememberLoader(type.getClassLoader());
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void rememberLoader(ClassLoader loader) {
        if (loader == null) return;
        for (ClassLoader seen : SEEN_LOADERS) {
            if (seen == loader) return;
        }
        if (SEEN_LOADERS.size() < 32) SEEN_LOADERS.add(loader);
    }

    private static String describeNamedClass(String className) {
        if (className.isEmpty()) return "classLoader=unresolved";
        for (int index = SEEN_LOADERS.size() - 1; index >= 0; index--) {
            ClassLoader loader = SEEN_LOADERS.get(index);
            try {
                Class<?> type = Class.forName(className, false, loader);
                if (type.getClassLoader() == loader) return describeClass(type);
            } catch (Throwable ignored) {
                // Probe only. A miss is itself evidence that this loader does not own the class.
            }
        }
        try {
            return describeClass(Class.forName(className, false, null));
        } catch (Throwable ignored) {
            return "class=" + className + " classLoader=unresolved";
        }
    }

    static String nativeClassName(String message) {
        if (message == null) return "";
        int method = message.indexOf('(');
        if (method < 0) return "";
        String prefix = message.substring(0, method).trim();
        int space = prefix.lastIndexOf(' ');
        if (space >= 0) prefix = prefix.substring(space + 1);
        int dot = prefix.lastIndexOf('.');
        if (dot <= 0) return "";
        return prefix.substring(0, dot);
    }

    private static boolean containsUnsatisfiedLink(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof UnsatisfiedLinkError) return true;
            String message = String.valueOf(cursor.getMessage());
            if (message.contains("No implementation found for")) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private static Throwable root(Throwable error) {
        Throwable cursor = error;
        while (cursor != null && cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor == null ? error : cursor;
    }

    private static String nativeLibraryDirectories(ClassLoader loader) {
        try {
            Field pathListField = findField(loader.getClass(), "pathList");
            if (pathListField == null) return "";
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            if (pathList == null) return "";
            Field dirsField = findField(pathList.getClass(), "nativeLibraryDirectories");
            if (dirsField == null) return "";
            dirsField.setAccessible(true);
            Object dirs = dirsField.get(pathList);
            return dirs == null ? "" : String.valueOf(dirs).replace(' ', ',');
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    private static String safe(String value) {
        if (value == null) return "";
        String normalized = value.replace('\n', ' ').replace('\r', ' ');
        return normalized.length() > 240 ? normalized.substring(0, 240) : normalized;
    }
}
