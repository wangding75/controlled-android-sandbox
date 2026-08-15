package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.runtime.guest.GuestRuntimeEnvironment;
import com.warden.controlledsandbox.runtime.guest.GuestActivityInstrumentation;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Version-gated, audited and rollback-safe bridge for the remaining private Activity fields. */
public final class ActivityFieldBridge {
    private static final int MIN_API = 26;
    private static final int MAX_AUDITED_API = 36;
    private static final List<String> HOST_FIELDS = List.of(
            "mToken", "mMainThread", "mInstrumentation", "mActivityInfo",
            "mFragments");
    private static final List<String> OPTIONAL_HOST_FIELDS = List.of("mCurrentConfig");

    private ActivityFieldBridge() { }

    static IBinder hostToken(Activity activity) {
        try {
            Field field = requireField(activity.getClass(), "mToken");
            field.setAccessible(true);
            Object value = field.get(activity);
            if (!(value instanceof IBinder token)) {
                throw new IllegalStateException("ACTIVITY_FRAMEWORK_TOKEN_INVALID");
            }
            return token;
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_FRAMEWORK_TOKEN_READ_FAILED", error);
        }
    }

    static BridgeReport install(Activity host, Activity guest,
                                GuestRuntimeEnvironment.Session session, String componentClass,
                                int callerTaskId) {
        int api = Build.VERSION.SDK_INT;
        if (api < MIN_API || api > MAX_AUDITED_API) {
            throw new IllegalStateException("UNSUPPORTED_ACTIVITY_BRIDGE_API:" + api);
        }
        LinkedHashMap<String, Object> direct = new LinkedHashMap<>();
        // Full Activity.attach() temporarily uses the attached Stub as framework transport.
        // Replace ContextWrapper.mBase before Guest code observes the Activity so identity and
        // storage calls terminate at the Guest Context rather than at the Host Stub.
        direct.put("mBase", session.context());
        // Activity.attach() constructs ContextThemeWrapper's cached Theme while the Stub is
        // still the temporary base. Replace that cache as well; otherwise AndroidX resolves
        // Host drawable/style IDs through Guest Resources during onCreate.
        direct.put("mTheme", session.context().getTheme());
        // ContextThemeWrapper also caches Resources independently of mBase. Full attach() has
        // initialized that cache from the temporary Stub, so project it together with mTheme;
        // otherwise Guest resource IDs (for example AppCompat vector drawables) are looked up
        // in the Host APK and fail with Resources.NotFoundException.
        direct.put("mResources", session.context().getResources());
        direct.put("mApplication", session.application());
        direct.put("mInstrumentation", new GuestActivityInstrumentation(session.context(), callerTaskId));
        Intent guestIntent = new Intent(host.getIntent());
        guestIntent.setComponent(new ComponentName(session.spec().packageName, componentClass));
        direct.put("mIntent", guestIntent);
        direct.put("mComponent", new ComponentName(session.spec().packageName, componentClass));
        direct.put("mTitle", componentClass.substring(componentClass.lastIndexOf('.') + 1));
        ActivityInfo projectedInfo = guestActivityInfo(host, session, componentClass);
        direct.put("mActivityInfo", projectedInfo);
        BridgeReport report = installFields(host, guest, HOST_FIELDS, OPTIONAL_HOST_FIELDS, direct, api);
        applyGuestTheme(guest, session, componentClass);
        return report;
    }

    /**
     * Projects Guest identity onto an Activity that was instantiated by the real
     * ActivityThread/Instrumentation path.  Unlike the legacy host-trampoline bridge this
     * method does not copy a host Activity's token, window or Fragment controller: ActivityThread
     * has already attached those framework-owned objects to the Guest instance.
     */
    public static BridgeReport installGuest(Activity guest,
                                            GuestRuntimeEnvironment.Session session,
                                            String componentClass, Intent intent,
                                            int callerTaskId) {
        if (guest == null) throw new IllegalArgumentException("guest is required");
        if (session == null) throw new IllegalArgumentException("session is required");
        if (componentClass == null || componentClass.trim().isEmpty()) {
            throw new IllegalArgumentException("componentClass is required");
        }
        int api = Build.VERSION.SDK_INT;
        if (api < MIN_API || api > MAX_AUDITED_API) {
            throw new IllegalStateException("UNSUPPORTED_ACTIVITY_BRIDGE_API:" + api);
        }
        Intent guestIntent = intent == null ? new Intent() : new Intent(intent);
        guestIntent.setComponent(new ComponentName(session.spec().packageName, componentClass));
        LinkedHashMap<String, Object> direct = new LinkedHashMap<>();
        direct.put("mBase", session.context());
        direct.put("mTheme", session.context().getTheme());
        direct.put("mResources", session.context().getResources());
        direct.put("mApplication", session.application());
        direct.put("mIntent", guestIntent);
        direct.put("mComponent", new ComponentName(session.spec().packageName, componentClass));
        direct.put("mTitle", componentClass.substring(componentClass.lastIndexOf('.') + 1));
        direct.put("mActivityInfo", guestActivityInfo(session, componentClass));
        BridgeReport report = installFields(guest, guest, List.of(), OPTIONAL_HOST_FIELDS,
                direct, api);
        applyGuestTheme(guest, session, componentClass);
        return report;
    }

    /**
     * Updates the ActivityClientRecord after Instrumentation has created the Guest object.
     * ActivityThread owns the record; CAS only validates the token and projects the Guest
     * component metadata into it. This is the ownership boundary used by the RD trace.
     */
    public static Bundle promoteFrameworkRecord(Activity guest,
                                                 GuestRuntimeEnvironment.Session session,
                                                 String componentClass, Intent intent) {
        if (guest == null || session == null) throw new IllegalArgumentException("Activity/session required");
        Bundle evidence = frameworkEvidence(guest);
        try {
            Object record = findActivityClientRecord(guest);
            if (record == null) throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_UNAVAILABLE");
            Field recordClassField = findField(record.getClass(), "activityInfo");
            if (recordClassField != null) {
                recordClassField.setAccessible(true);
                recordClassField.set(record, guestActivityInfo(session, componentClass));
            }
            Field intentField = findField(record.getClass(), "intent");
            if (intentField != null) {
                intentField.setAccessible(true);
                intentField.set(record, intent == null ? new Intent() : new Intent(intent));
            }
            if (session.loadedApkProjection() != null) {
                Field packageInfoField = findField(record.getClass(), "packageInfo");
                if (packageInfoField != null) {
                    packageInfoField.setAccessible(true);
                    packageInfoField.set(record, session.loadedApkProjection());
                }
            }
            Field activityField = findField(record.getClass(), "activity");
            if (activityField != null) {
                activityField.setAccessible(true);
                Object current = activityField.get(record);
                if (current != guest) {
                    throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_ACTIVITY_MISMATCH");
                }
            }
            // Activity.attach() accepts ActivityClientRecord's preserved window. A Stub route
            // can arrive with that slot populated by a previous host trampoline, while the
            // newly instantiated Guest has never registered that DecorView with WMG. Clear the
            // preserved record so ActivityThread.handleResumeActivity obtains the Guest window
            // through its normal r.window == null branch and owns addView/removeView symmetrically.
            clearStaleFrameworkWindow(record, guest);
            evidence.putBoolean("frameworkRecordPromoted", true);
            evidence.putString("frameworkComponentClass", componentClass);
            evidence.putString("frameworkPackageName", session.spec().packageName);
            return evidence;
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_PROMOTION_FAILED", error);
        }
    }

    /** Returns framework-owned ActivityClientRecord evidence for the Activity token. */
    public static Bundle frameworkEvidence(Activity activity) {
        if (activity == null) throw new IllegalArgumentException("activity is required");
        try {
            Object record = findActivityClientRecord(activity);
            if (record == null) throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_NOT_FOUND");
            Field tokenField = findField(activity.getClass(), "mToken");
            tokenField.setAccessible(true);
            Object token = tokenField.get(activity);
            Field recordActivity = findField(record.getClass(), "activity");
            recordActivity.setAccessible(true);
            if (recordActivity.get(record) != activity) {
                throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_ACTIVITY_MISMATCH");
            }
            Bundle evidence = new Bundle();
            evidence.putString("frameworkRecordClass", record.getClass().getName());
            evidence.putString("frameworkActivityClass", activity.getClass().getName());
            evidence.putString("frameworkToken", String.valueOf(token));
            Field info = findField(record.getClass(), "activityInfo");
            if (info != null) {
                info.setAccessible(true);
                Object value = info.get(record);
                if (value instanceof ActivityInfo activityInfo) {
                    evidence.putString("frameworkRecordPackage", activityInfo.packageName);
                    evidence.putString("frameworkRecordName", activityInfo.name);
                }
            }
            android.view.Window window = activity.getWindow();
            android.view.View decor = window == null ? null : window.getDecorView();
            evidence.putBoolean("windowAttached", decor != null && decor.isAttachedToWindow());
            evidence.putBoolean("windowCreated", window != null);
            return evidence;
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_FRAMEWORK_EVIDENCE_FAILED", error);
        }
    }

    private static Object findActivityClientRecord(Activity activity) throws Exception {
        Class<?> activityThreadType = Class.forName("android.app.ActivityThread");
        Field currentField = findField(activityThreadType, "sCurrentActivityThread");
        if (currentField == null) throw new NoSuchFieldException("ActivityThread.sCurrentActivityThread");
        currentField.setAccessible(true);
        Object thread = currentField.get(null);
        if (thread == null) throw new IllegalStateException("ACTIVITY_THREAD_CURRENT_NULL");
        Field activitiesField = findField(thread.getClass(), "mActivities");
        if (activitiesField == null) throw new NoSuchFieldException("ActivityThread.mActivities");
        activitiesField.setAccessible(true);
        Object activities = activitiesField.get(thread);
        if (activities == null) throw new IllegalStateException("ACTIVITY_THREAD_ACTIVITIES_NULL");
        Field tokenField = findField(activity.getClass(), "mToken");
        if (tokenField == null) throw new NoSuchFieldException("Activity.mToken");
        tokenField.setAccessible(true);
        Object token = tokenField.get(activity);
        if (token == null) throw new IllegalStateException("ACTIVITY_FRAMEWORK_TOKEN_NULL");
        Method get = activities.getClass().getMethod("get", Object.class);
        Object record = get.invoke(activities, token);
        if (record == null) throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_NOT_FOUND");
        Field recordActivity = findField(record.getClass(), "activity");
        if (recordActivity == null) throw new NoSuchFieldException("ActivityClientRecord.activity");
        recordActivity.setAccessible(true);
        if (recordActivity.get(record) != activity) {
            throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_ACTIVITY_MISMATCH");
        }
        return record;
    }

    /**
     * Prevents ActivityThread from asking WindowManager to remove a DecorView that never made it
     * through addView (for example an Activity that starts another Activity from onCreate and is
     * hidden before its first visible frame). This only clears framework bookkeeping when the
     * current DecorView is demonstrably not attached or registered; visible Guest windows are
     * left entirely to Android's normal destroy path.
     */
    public static void repairFrameworkWindowBeforeDestroy(Activity activity) {
        if (activity == null) return;
        try {
            android.view.Window window = activity.getWindow();
            android.view.View decor = window == null ? null : window.getDecorView();
            if (decor == null || decor.isAttachedToWindow() || isWindowRegistered(decor)) return;
            setOptionalBoolean(activity, "mWindowAdded", false);
            setOptionalObject(activity, "mDecor", null);
            Class<?> threadType = Class.forName("android.app.ActivityThread");
            Field current = findField(threadType, "sCurrentActivityThread");
            if (current == null) return;
            current.setAccessible(true);
            Object thread = current.get(null);
            if (thread == null) return;
            Field activities = findField(thread.getClass(), "mActivities");
            activities.setAccessible(true);
            Object map = activities.get(thread);
            Field token = findField(activity.getClass(), "mToken");
            token.setAccessible(true);
            Object record = map.getClass().getMethod("get", Object.class).invoke(map, token.get(activity));
            if (record == null) return;
            setOptionalObject(record, "window", null);
            setOptionalBoolean(record, "mPreserveWindow", false);
            setOptionalObject(record, "mPendingRemoveWindow", null);
            setOptionalObject(record, "mPendingRemoveWindowManager", null);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_FRAMEWORK_ACTIVITY", "window destroy repair skipped", error);
        }
    }

    /**
     * Activity.attach() may initialize ContextThemeWrapper's theme bookkeeping from the Host
     * transport before the audited field projection runs. Re-apply the Guest component theme
     * through the public API after projection so AndroidX/AppCompat sees the Guest style parent
     * during both creation and teardown.
     */
    private static void applyGuestTheme(Activity guest,
                                        GuestRuntimeEnvironment.Session session,
                                        String componentClass) {
        int themeResId = 0;
        for (VirtualComponentSnapshot component : session.spec().packageState().components()) {
            if ("ACTIVITY".equals(component.type())
                    && componentClass.equals(component.className())) {
                themeResId = component.themeResId();
                break;
            }
        }
        if (themeResId != 0) guest.setTheme(themeResId);
    }

    private static void clearStaleFrameworkWindow(Object record, Activity activity) throws Exception {
        android.view.Window window = activity.getWindow();
        android.view.View decor = window == null ? null : window.getDecorView();
        if (decor != null && (decor.isAttachedToWindow() || isWindowRegistered(decor))) return;
        Field recordWindow = findField(record.getClass(), "window");
        if (recordWindow != null) {
            recordWindow.setAccessible(true);
            recordWindow.set(record, null);
        }
        setOptionalBoolean(record, "mPreserveWindow", false);
        setOptionalObject(record, "mPendingRemoveWindow", null);
        setOptionalObject(record, "mPendingRemoveWindowManager", null);
        setOptionalBoolean(activity, "mWindowAdded", false);
        setOptionalObject(activity, "mDecor", null);
    }

    private static boolean isWindowRegistered(android.view.View decor) {
        try {
            Object global = Class.forName("android.view.WindowManagerGlobal")
                    .getDeclaredMethod("getInstance").invoke(null);
            Field views = requireField(global.getClass(), "mViews");
            views.setAccessible(true);
            Object value = views.get(global);
            if (!(value instanceof java.util.List<?> list)) return false;
            for (Object item : list) if (item == decor) return true;
        } catch (Throwable ignored) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
        }
        return false;
    }

    private static void setOptionalBoolean(Object target, String name, boolean value) throws Exception {
        Field field = findField(target.getClass(), name);
        if (field == null) return;
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void setOptionalObject(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        if (field == null) return;
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Activity.attach() starts from the host StubActivity metadata.  Leaving that metadata on
     * the Guest Activity makes framework code such as WebView's BuildInfo query the host package.
     * Keep the host's audited framework identity fields, but leave the Guest-owned Window and
     * WindowManager created by Activity.attach() intact. Sharing the Stub Window would make two
     * Android Activity records own one DecorView and causes WindowManagerGlobal cleanup failures
     * when a Guest Activity starts a nested Stub.
     */
    private static ActivityInfo guestActivityInfo(Activity host,
                                                  GuestRuntimeEnvironment.Session session,
                                                  String componentClass) {
        try {
            Field field = requireField(host.getClass(), "mActivityInfo");
            field.setAccessible(true);
            Object value = field.get(host);
            if (!(value instanceof ActivityInfo source)) {
                throw new IllegalStateException("ACTIVITY_INFO_SOURCE_INVALID");
            }
            ActivityInfo projected = new ActivityInfo(source);
            ApplicationInfo guestApplication = session.context().getApplicationInfo();
            projected.applicationInfo = guestApplication;
            projected.packageName = session.spec().packageName;
            projected.name = componentClass;
            projected.processName = guestApplication.processName == null
                    ? session.spec().packageName : guestApplication.processName;
            for (VirtualComponentSnapshot component : session.spec().packageState().components()) {
                if (!"ACTIVITY".equals(component.type())
                        || !componentClass.equals(component.className())) continue;
                // A Stub Activity's framework metadata describes the host trampoline,
                // not the Guest component. Project the virtual package authority's
                // exported/enabled/security identity onto the Guest Activity.
                projected.exported = component.exported();
                projected.enabled = component.enabled();
                projected.permission = component.permission().isEmpty()
                        ? null : component.permission();
                projected.processName = component.processName().isEmpty()
                        ? projected.processName : component.processName();
                projected.theme = component.themeResId();
                break;
            }
            return projected;
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_INFO_GUEST_PROJECTION_FAILED", error);
        }
    }

    private static ActivityInfo guestActivityInfo(GuestRuntimeEnvironment.Session session,
                                                  String componentClass) {
        ActivityInfo projected = new ActivityInfo();
        ApplicationInfo application = new ApplicationInfo(session.context().getApplicationInfo());
        application.packageName = session.spec().packageName;
        application.processName = session.spec().processName();
        projected.applicationInfo = application;
        projected.packageName = session.spec().packageName;
        projected.name = componentClass;
        projected.processName = session.spec().processName();
        for (VirtualComponentSnapshot component : session.spec().packageState().components()) {
            if (!"ACTIVITY".equals(component.type()) || !componentClass.equals(component.className())) continue;
            projected.exported = component.exported();
            projected.enabled = component.enabled();
            projected.permission = component.permission().isEmpty() ? null : component.permission();
            projected.processName = component.processName().isEmpty()
                    ? projected.processName : component.processName();
            projected.theme = component.themeResId();
            break;
        }
        return projected;
    }

    static BridgeReport installFields(Object host, Object guest, List<String> requiredHostFields,
                                      List<String> optionalHostFields, Map<String, Object> directValues,
                                      int apiLevel) {
        if (apiLevel < MIN_API || apiLevel > MAX_AUDITED_API) {
            throw new IllegalStateException("UNSUPPORTED_ACTIVITY_BRIDGE_API:" + apiLevel);
        }
        java.util.Objects.requireNonNull(host, "host");
        java.util.Objects.requireNonNull(guest, "guest");
        List<Write> writes = new ArrayList<>();
        List<String> optionalMissing = new ArrayList<>();
        try {
            for (String name : requiredHostFields) {
                Field source = requireField(host.getClass(), name);
                Field target = requireField(guest.getClass(), name);
                source.setAccessible(true);
                target.setAccessible(true);
                Object value = source.get(host);
                if (!assignable(target, value)) {
                    if ("mFragments".equals(name)) {
                        // AndroidX FragmentActivity owns a different FragmentController type;
                        // its guest-side controller must not be replaced with the host platform
                        // controller. Keep the mismatch visible in the bridge report.
                        optionalMissing.add(name + ":TYPE_MISMATCH");
                        continue;
                    }
                    ensureAssignable(target, value, name);
                }
                writes.add(new Write(target, guest, target.get(guest), value));
            }
            for (String name : optionalHostFields) {
                Field source = findField(host.getClass(), name);
                Field target = findField(guest.getClass(), name);
                if (source == null || target == null) {
                    optionalMissing.add(name);
                    continue;
                }
                source.setAccessible(true);
                target.setAccessible(true);
                Object value = source.get(host);
                ensureAssignable(target, value, name);
                writes.add(new Write(target, guest, target.get(guest), value));
            }
            for (Map.Entry<String, Object> entry : directValues.entrySet()) {
                Field target = requireField(guest.getClass(), entry.getKey());
                target.setAccessible(true);
                ensureAssignable(target, entry.getValue(), entry.getKey());
                writes.add(new Write(target, guest, target.get(guest), entry.getValue()));
            }
            int applied = 0;
            try {
                for (Write write : writes) {
                    write.field.set(write.target, write.value);
                    applied++;
                }
            } catch (Throwable failure) {
                try {
                    rollback(writes, applied);
                } finally {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(failure);
                }
                throw failure;
            }
            return new BridgeReport(apiLevel, writes.size(), List.copyOf(optionalMissing));
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_FIELD_BRIDGE_FAILED", error);
        }
    }

    private static void rollback(List<Write> writes, int applied) {
        for (int index = applied - 1; index >= 0; index--) {
            Write write = writes.get(index);
            try { write.field.set(write.target, write.previous); } catch (Throwable ignored) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored); }
        }
    }

    private static void ensureAssignable(Field field, Object value, String name) {
        if (!assignable(field, value)) {
            throw new IllegalStateException("ACTIVITY_FIELD_TYPE_MISMATCH:" + name
                    + ":" + field.getType().getName() + "<-" + value.getClass().getName());
        }
    }

    private static boolean assignable(Field field, Object value) {
        return value == null || boxed(field.getType()).isInstance(value);
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static Field requireField(Class<?> type, String name) {
        Field field = findField(type, name);
        if (field == null) throw new IllegalStateException("ACTIVITY_FIELD_MISSING:" + name);
        return field;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        return null;
    }

    record BridgeReport(int apiLevel, int appliedFieldCount, List<String> optionalMissingFields) {
        BridgeReport {
            optionalMissingFields = List.copyOf(optionalMissingFields);
            if (appliedFieldCount < 1) throw new IllegalArgumentException("appliedFieldCount must be positive");
        }
    }

    private record Write(Field field, Object target, Object previous, Object value) { }
}
