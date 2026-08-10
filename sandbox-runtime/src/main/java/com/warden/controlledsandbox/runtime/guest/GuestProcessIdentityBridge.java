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
    private final ApplicationInfo boundApplicationInfo;
    private final String originalBoundPackageName;
    private final Field boundProcessNameField;
    private final Object originalBoundProcessName;
    private final Application guestApplication;
    private final String guestPackageName;
    private final String guestProcessName;
    private boolean closed;

    private GuestProcessIdentityBridge(
            Object activityThread,
            Field initialApplicationField,
            Object originalInitialApplication,
            Field boundApplicationInfoField,
            Object boundApplication,
            ApplicationInfo boundApplicationInfo,
            String originalBoundPackageName,
            Field boundProcessNameField,
            Object originalBoundProcessName,
            Application guestApplication,
            String guestPackageName,
            String guestProcessName) {
        this.activityThread = activityThread;
        this.initialApplicationField = initialApplicationField;
        this.originalInitialApplication = originalInitialApplication;
        this.boundApplicationInfoField = boundApplicationInfoField;
        this.boundApplication = boundApplication;
        this.boundApplicationInfo = boundApplicationInfo;
        this.originalBoundPackageName = originalBoundPackageName;
        this.boundProcessNameField = boundProcessNameField;
        this.originalBoundProcessName = originalBoundProcessName;
        this.guestApplication = guestApplication;
        this.guestPackageName = guestPackageName;
        this.guestProcessName = guestProcessName;
    }

    static GuestProcessIdentityBridge install(Application guestApplication,
                                               GuestPackageSpec spec) throws Exception {
        if (guestApplication == null) throw new IllegalArgumentException("guestApplication is required");
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
        initial.set(current, guestApplication);

        Object bound = null;
        Field boundInfoField = null;
        ApplicationInfo boundInfo = null;
        String originalPackage = null;
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
            boundInfo = (ApplicationInfo) value;
            originalPackage = boundInfo.packageName;
            boundInfo.packageName = spec.packageName;
            try {
                processNameField = findField(bound.getClass(), "processName");
                processNameField.setAccessible(true);
                originalProcessName = processNameField.get(bound);
                processNameField.set(bound, spec.processName);
            } catch (NoSuchFieldException ignored) {
                // Older platform shapes do not expose a separate bound process name.
            }
        } catch (Throwable error) {
            if (boundInfo != null && spec.packageName.equals(boundInfo.packageName)) {
                boundInfo.packageName = originalPackage;
            }
            if (processNameField != null && bound != null
                    && spec.processName.equals(processNameField.get(bound))) {
                processNameField.set(bound, originalProcessName);
            }
            initial.set(current, originalInitial);
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw error;
        }

        android.util.Log.i("CS_GUEST_PROCESS_IDENTITY", "installed package=" + spec.packageName
                + " process=" + spec.processName);
        return new GuestProcessIdentityBridge(current, initial, originalInitial,
                boundInfoField, bound, boundInfo, originalPackage, processNameField,
                originalProcessName, guestApplication, spec.packageName, spec.processName);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (boundApplicationInfoField != null && boundApplication != null
                    && boundApplicationInfoField.get(boundApplication) == boundApplicationInfo
                    && guestPackageName.equals(boundApplicationInfo.packageName)) {
                boundApplicationInfo.packageName = originalBoundPackageName;
            }
            if (boundProcessNameField != null && boundApplication != null
                    && guestProcessName.equals(boundProcessNameField.get(boundApplication))) {
                boundProcessNameField.set(boundApplication, originalBoundProcessName);
            }
            if (initialApplicationField.get(activityThread) == guestApplication) {
                initialApplicationField.set(activityThread, originalInitialApplication);
            }
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_GUEST_PROCESS_IDENTITY", "restore failed", error);
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
