package com.warden.controlledsandbox.runtime.guest;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import java.lang.reflect.Field;

/**
 * Gives framework code in a Guest slot the same virtual process identity as Guest Context.
 *
 * <p>Android's WebView and dex-loader paths consult ActivityThread rather than the Activity's
 * Context. Leaving the Host Application in these process-global fields makes those paths issue
 * Host package queries through the already-isolated PackageManager. The bridge changes only the
 * current Guest slot, records every field it changes, and restores the exact values on session
 * shutdown. It never changes Process.myUid() or any Host process identity used for Binder
 * transport.</p>
 */
final class GuestProcessIdentityBridge implements AutoCloseable {
    private final Object activityThread;
    private final Field initialApplicationField;
    private final Object originalInitialApplication;
    private final Field boundApplicationInfoField;
    private final Object boundApplication;
    private final ApplicationInfo originalBoundApplicationInfo;
    private final ApplicationInfo guestBoundApplicationInfo;
    private final Field boundProcessNameField;
    private final Object originalBoundProcessName;
    private Application guestApplication;
    private final String guestPackageName;
    private final String guestProcessName;
    private final String originalOsProcessName;
    private final boolean osProcessNamePublished;
    private boolean closed;

    private GuestProcessIdentityBridge(
            Object activityThread,
            Field initialApplicationField,
            Object originalInitialApplication,
            Field boundApplicationInfoField,
            Object boundApplication,
            ApplicationInfo originalBoundApplicationInfo,
            ApplicationInfo guestBoundApplicationInfo,
            Field boundProcessNameField,
            Object originalBoundProcessName,
            Application guestApplication,
            String guestPackageName,
            String guestProcessName,
            String originalOsProcessName,
            boolean osProcessNamePublished) {
        this.activityThread = activityThread;
        this.initialApplicationField = initialApplicationField;
        this.originalInitialApplication = originalInitialApplication;
        this.boundApplicationInfoField = boundApplicationInfoField;
        this.boundApplication = boundApplication;
        this.originalBoundApplicationInfo = originalBoundApplicationInfo;
        this.guestBoundApplicationInfo = guestBoundApplicationInfo;
        this.boundProcessNameField = boundProcessNameField;
        this.originalBoundProcessName = originalBoundProcessName;
        this.guestApplication = guestApplication;
        this.guestPackageName = guestPackageName;
        this.guestProcessName = guestProcessName;
        this.originalOsProcessName = originalOsProcessName == null ? "" : originalOsProcessName;
        this.osProcessNamePublished = osProcessNamePublished;
    }

    static GuestProcessIdentityBridge bind(ApplicationInfo guestInfo, GuestPackageSpec spec)
            throws Exception {
        return install(null, guestInfo, spec);
    }

    void attachApplication(Application guestApplication) throws Exception {
        if (guestApplication == null) throw new IllegalArgumentException("guestApplication is required");
        if (closed) throw new IllegalStateException("GUEST_PROCESS_IDENTITY_CLOSED");
        initialApplicationField.set(activityThread, guestApplication);
        this.guestApplication = guestApplication;
        android.util.Log.i("CS_GUEST_PROCESS_IDENTITY", "application attached package="
                + guestPackageName + " process=" + guestProcessName);
    }

    static GuestProcessIdentityBridge install(Application guestApplication,
                                               ApplicationInfo guestInfo,
                                               GuestPackageSpec spec) throws Exception {
        if (guestInfo == null) throw new IllegalArgumentException("guestInfo is required");
        if (spec == null) throw new IllegalArgumentException("spec is required");
        Class<?> activityThreadType = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Method currentMethod = activityThreadType.getDeclaredMethod(
                "currentActivityThread");
        currentMethod.setAccessible(true);
        Object current = currentMethod.invoke(null);
        if (current == null) throw new IllegalStateException("GUEST_ACTIVITY_THREAD_UNAVAILABLE");

        Field initial = findField(activityThreadType, "mInitialApplication");
        initial.setAccessible(true);
        Object originalInitial = initial.get(current);
        if (guestApplication != null) initial.set(current, guestApplication);

        Object bound = null;
        Field boundInfoField = null;
        ApplicationInfo originalInfo = null;
        ApplicationInfo boundInfo = null;
        Field processNameField = null;
        Object originalProcessName = null;
        try {
            Field boundField = findField(activityThreadType, "mBoundApplication");
            boundField.setAccessible(true);
            bound = boundField.get(current);
            if (bound == null) throw new IllegalStateException("GUEST_BOUND_APPLICATION_UNAVAILABLE");
            boundInfoField = findField(bound.getClass(), "appInfo");
            boundInfoField.setAccessible(true);
            Object value = boundInfoField.get(bound);
            if (!(value instanceof ApplicationInfo)) {
                throw new IllegalStateException("GUEST_BOUND_APPLICATION_INFO_UNAVAILABLE");
            }
            originalInfo = (ApplicationInfo) value;
            if (guestInfo == null || !spec.packageName.equals(guestInfo.packageName)) {
                throw new IllegalStateException("GUEST_APPLICATION_INFO_PROJECTION_INVALID");
            }
            // ActivityThread's bound application info is consulted by WebViewFactory, the
            // linker/dex loader and framework package lookups.  Changing only packageName
            // leaves the host APK/data/native paths behind, producing a mixed identity in
            // native WebView startup.  Publish the complete Guest projection and restore the
            // original object on teardown so the host container remains untouched.
            boundInfoField.set(bound, guestInfo);
            boundInfo = guestInfo;
            try {
                processNameField = findField(bound.getClass(), "processName");
                processNameField.setAccessible(true);
                originalProcessName = processNameField.get(bound);
                processNameField.set(bound, spec.processName);
            } catch (NoSuchFieldException ignored) {
                // Older platform shapes do not expose a separate bound process name.
            }
        } catch (Throwable error) {
            if (boundInfoField != null && bound != null && boundInfo != null
                    && boundInfoField.get(bound) == boundInfo) {
                boundInfoField.set(bound, originalInfo);
            }
            if (processNameField != null && bound != null
                    && spec.processName.equals(processNameField.get(bound))) {
                processNameField.set(bound, originalProcessName);
            }
            initial.set(current, originalInitial);
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw error;
        }

        String originalOsProcessName = readOsProcessName();
        boolean osProcessNamePublished = publishOsProcessName(spec.processName);
        publishEmbeddedProcessIdentity(activityThreadType, current, bound,
                spec.packageName, spec.processName, originalOsProcessName);
        String frameworkProcessName = "";
        try {
            java.lang.reflect.Method processName = activityThreadType.getDeclaredMethod("getProcessName");
            processName.setAccessible(true);
            frameworkProcessName = String.valueOf(processName.invoke(current));
        } catch (Throwable ignored) {
            frameworkProcessName = "unavailable:" + ignored.getClass().getSimpleName();
        }
        android.util.Log.i("CS_GUEST_PROCESS_IDENTITY", "installed package=" + spec.packageName
                + " process=" + spec.processName + " frameworkProcess=" + frameworkProcessName
                + " osProcess=" + readOsProcessName() + " argv0Published=" + osProcessNamePublished);
        return new GuestProcessIdentityBridge(current, initial, originalInitial,
                boundInfoField, bound, originalInfo, boundInfo, processNameField,
                originalProcessName, guestApplication, spec.packageName, spec.processName,
                originalOsProcessName, osProcessNamePublished);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (boundApplicationInfoField != null && boundApplication != null
                    && boundApplicationInfoField.get(boundApplication) == guestBoundApplicationInfo) {
                boundApplicationInfoField.set(boundApplication, originalBoundApplicationInfo);
            }
            if (boundProcessNameField != null && boundApplication != null
                    && guestProcessName.equals(boundProcessNameField.get(boundApplication))) {
                boundProcessNameField.set(boundApplication, originalBoundProcessName);
            }
            if (initialApplicationField.get(activityThread) == guestApplication) {
                initialApplicationField.set(activityThread, originalInitialApplication);
            }
            if (osProcessNamePublished && !originalOsProcessName.isEmpty()
                    && !originalOsProcessName.equals(guestProcessName)) {
                publishOsProcessName(originalOsProcessName);
            }
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_GUEST_PROCESS_IDENTITY", "restore failed", error);
        }
    }

    /**
     * handleBindApplication calls Process.setArgV0(processName) before Application
     * construction. Apps that read cmdline / Process.myProcessName() observe that
     * value, not only ActivityThread.mBoundApplication.processName.
     */
    static String readOsProcessName() {
        try {
            java.lang.reflect.Method myProcessName = android.os.Process.class.getDeclaredMethod(
                    "myProcessName");
            myProcessName.setAccessible(true);
            Object value = myProcessName.invoke(null);
            if (value != null) {
                String name = String.valueOf(value).trim();
                if (!name.isEmpty()) return name;
            }
        } catch (Throwable ignored) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
        }
        try (java.io.FileInputStream input = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] buffer = new byte[256];
            int read = input.read(buffer);
            if (read > 0) {
                int end = 0;
                while (end < read && buffer[end] != 0) end++;
                return new String(buffer, 0, end, java.nio.charset.StandardCharsets.UTF_8).trim();
            }
        } catch (Throwable ignored) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
        }
        return "";
    }

    static boolean publishOsProcessName(String processName) {
        if (processName == null || processName.trim().isEmpty()) return false;
        String name = processName.trim();
        boolean published = false;
        try {
            java.lang.reflect.Method setArgV0 = android.os.Process.class.getDeclaredMethod(
                    "setArgV0", String.class);
            setArgV0.setAccessible(true);
            setArgV0.invoke(null, name);
            published = true;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_GUEST_PROCESS_IDENTITY", "setArgV0 unavailable", error);
        }
        try {
            Class<?> ddm = Class.forName("android.ddm.DdmHandleAppName");
            try {
                java.lang.reflect.Method setAppName = ddm.getDeclaredMethod(
                        "setAppName", String.class, int.class);
                setAppName.setAccessible(true);
                setAppName.invoke(null, name, 0);
            } catch (NoSuchMethodException ignored) {
                java.lang.reflect.Method setAppName = ddm.getDeclaredMethod("setAppName", String.class);
                setAppName.setAccessible(true);
                setAppName.invoke(null, name);
            }
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_GUEST_PROCESS_IDENTITY", "DdmHandleAppName unavailable", error);
        }
        return published;
    }

    /**
     * Some OEM ActivityThread builds keep a second process/package identity used by
     * crash/ANR helpers. handleBindApplication updates that together with argv0.
     */
    static void publishEmbeddedProcessIdentity(Class<?> activityThreadType, Object activityThread,
                                               Object boundApplication, String packageName,
                                               String processName, String hostProcessName) {
        if (activityThreadType == null || activityThread == null) return;
        tryInvokeEmbeddedIdentity(activityThreadType, activityThread, "setEmbeddedParam",
                new Class<?>[] { String.class, String.class, boolean.class, boolean.class },
                new Object[] { packageName, processName, Boolean.FALSE, Boolean.FALSE });
        String published = "";
        Class<?> cursor = activityThreadType;
        while (cursor != null) {
            for (java.lang.reflect.Method method : cursor.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.regionMatches(true, 0, "setEmbedded", 0, 11)
                        && !"setProcessName".equals(name)) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                Object[] args = new Object[types.length];
                int strings = 0;
                boolean compatible = true;
                for (int index = 0; index < types.length; index++) {
                    if (types[index] == String.class) {
                        args[index] = strings++ == 0 ? packageName : processName;
                    } else if (types[index] == boolean.class || types[index] == Boolean.class) {
                        args[index] = Boolean.FALSE;
                    } else if (types.length == 1 && boundApplication != null
                            && types[index].isInstance(boundApplication)) {
                        args[index] = boundApplication;
                        strings++;
                    } else if (types.length == 1) {
                        args[index] = embeddedIdentityArgument(types[index], packageName, processName);
                        if (args[index] == null) compatible = false;
                        else strings++;
                    } else {
                        compatible = false;
                        break;
                    }
                }
                if (!compatible || strings < 1) {
                    android.util.Log.i("CS_GUEST_PROCESS_IDENTITY",
                            "embedded helper skipped name=" + name + " args=" + types.length
                                    + " first=" + (types.length == 0 ? "" : types[0].getName()));
                    continue;
                }
                try {
                    method.setAccessible(true);
                    method.invoke(activityThread, args);
                    published = name;
                } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                    android.util.Log.w("CS_GUEST_PROCESS_IDENTITY",
                            "embedded helper failed name=" + name, error);
                }
            }
            cursor = cursor.getSuperclass();
        }
        overlayHostProcessNameFields(activityThread, boundApplication, hostProcessName, processName);
        if (!published.isEmpty()) {
            android.util.Log.i("CS_GUEST_PROCESS_IDENTITY",
                    "embedded identity published via=" + published
                            + " package=" + packageName + " process=" + processName);
        }
    }

    private static void tryInvokeEmbeddedIdentity(Class<?> type, Object target, String name,
                                                  Class<?>[] signature, Object[] args) {
        try {
            java.lang.reflect.Method method = type.getDeclaredMethod(name, signature);
            method.setAccessible(true);
            method.invoke(target, args);
            android.util.Log.i("CS_GUEST_PROCESS_IDENTITY",
                    "embedded identity published via=" + name + " arity=" + signature.length);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_GUEST_PROCESS_IDENTITY",
                    "embedded helper failed name=" + name + " arity=" + signature.length, error);
        }
    }

    private static void overlayHostProcessNameFields(Object activityThread, Object boundApplication,
                                                     String hostProcessName, String guestProcessName) {
        if (hostProcessName == null || hostProcessName.isEmpty()
                || hostProcessName.equals(guestProcessName)) {
            return;
        }
        int replaced = replaceStringFields(activityThread, hostProcessName, guestProcessName)
                + replaceStringFields(boundApplication, hostProcessName, guestProcessName);
        if (replaced > 0) {
            android.util.Log.i("CS_GUEST_PROCESS_IDENTITY",
                    "overlaid host process fields count=" + replaced
                            + " from=" + hostProcessName + " to=" + guestProcessName);
        }
    }

    private static int replaceStringFields(Object target, String from, String to) {
        if (target == null) return 0;
        int replaced = 0;
        Class<?> cursor = target.getClass();
        while (cursor != null) {
            for (Field field : cursor.getDeclaredFields()) {
                if (field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (from.equals(value)) {
                        field.set(target, to);
                        replaced++;
                    }
                } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
            }
            cursor = cursor.getSuperclass();
        }
        return replaced;
    }

    private static Object embeddedIdentityArgument(Class<?> type, String packageName,
                                                   String processName) {
        if (type == null) return null;
        if (type == String.class) return processName;
        try {
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object value = constructor.newInstance();
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != String.class) continue;
                String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                field.setAccessible(true);
                if (name.contains("process")) field.set(value, processName);
                else if (name.contains("package")) field.set(value, packageName);
            }
            return value;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_GUEST_PROCESS_IDENTITY",
                    "embedded argument unavailable type=" + type.getName(), error);
            return null;
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
}
