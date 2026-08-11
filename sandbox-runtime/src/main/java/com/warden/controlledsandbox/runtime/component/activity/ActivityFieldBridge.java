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
import android.os.IBinder;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Version-gated, audited and rollback-safe bridge for the remaining private Activity fields. */
public final class ActivityFieldBridge {
    private static final int MIN_API = 26;
    private static final int MAX_AUDITED_API = 36;
    private static final List<String> HOST_FIELDS = List.of(
            "mWindow", "mWindowManager", "mToken", "mMainThread", "mInstrumentation", "mActivityInfo",
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
        // ActivityThread's current ActivityClientRecord normally retains the same
        // ActivityInfo object that the host Stub received at attach time. Project
        // that internal record too, so framework/app checks observe the virtual
        // component instead of the private Stub declaration.
        Field hostInfo = requireField(host.getClass(), "mActivityInfo");
        hostInfo.setAccessible(true);
        try {
            hostInfo.set(host, projectedInfo);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_HOST_INFO_PROJECTION_FAILED", error);
        }
        return report;
    }

    /**
     * Activity.attach() starts from the host StubActivity metadata.  Leaving that metadata on
     * the Guest Activity makes framework code such as WebView's BuildInfo query the host package.
     * Keep the host's audited window/theme fields, but replace only the identity-bearing portion
     * with the Guest package and its Guest ApplicationInfo.
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
