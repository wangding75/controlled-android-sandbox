package com.warden.controlledsandbox.framework.core;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import com.warden.controlledsandbox.contract.VirtualWidgetSnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageEventSnapshot;
import com.warden.controlledsandbox.contract.VirtualUserProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Reflection-only Android object projection for application-environment services. */
final class FrameworkApplicationEnvironmentObjectFactory {
    private FrameworkApplicationEnvironmentObjectFactory() { }

    static Object userHandle(Class<?> type, int userId) {
        if (type == int.class || type == Integer.class) return userId;
        if (type == long.class || type == Long.class) return (long) userId;
        if (type == Object.class) return new ProjectedUserHandle(userId);
        Object value = construct(type, new Class<?>[]{int.class}, new Object[]{userId});
        if (value == null) value = construct(type, new Class<?>[0], new Object[0]);
        if (value == null) throw unsupported("USER_HANDLE", type);
        write(value, userId, "mHandle", "identifier", "id", "mIdentifier");
        return value;
    }

    static Object userInfo(Class<?> type, VirtualUserProfileSnapshot profile) {
        if (type == Object.class) return new ProjectedUserInfo(profile.userId(), profile.serialNumber(),
                profile.name(), profile.flags(), profile.running(), profile.unlocked(), profile.quietMode());
        Object value = construct(type, new Class<?>[]{int.class, String.class, int.class},
                new Object[]{profile.userId(), profile.name(), profile.flags()});
        if (value == null) value = construct(type, new Class<?>[0], new Object[0]);
        if (value == null) throw unsupported("USER_INFO", type);
        write(value, profile.userId(), "id", "mId", "userId");
        write(value, profile.serialNumber(), "serialNumber", "mSerialNumber");
        write(value, profile.name(), "name", "mName");
        write(value, profile.flags(), "flags", "mFlags");
        return value;
    }

    static Object launcherActivity(Class<?> type, GuestIdentity identity) {
        VirtualPackageMetadata metadata = identity.packageMetadata();
        String launcher = metadata.launcherActivity();
        if (launcher == null || launcher.isBlank()) return null;
        if (type == Object.class) return new ProjectedLauncherActivity(identity.packageName(), launcher,
                identity.virtualUserId(), true);
        Object value = construct(type, new Class<?>[0], new Object[0]);
        if (value == null) throw unsupported("LAUNCHER_ACTIVITY", type);
        write(value, identity.packageName(), "packageName", "mPackageName");
        write(value, launcher, "className", "mClassName", "activityName");
        write(value, userHandle(fieldType(value, "mUser", "user", "userHandle"), identity.virtualUserId()),
                "mUser", "user", "userHandle");
        return value;
    }

    /**
     * Projects the element contract returned by ILauncherApps rather than the public
     * LauncherApps manager contract. Android 12's manager converts a
     * LauncherActivityInfoInternal supplied by the Binder service into LauncherActivityInfo;
     * returning a manager-layer DTO here fails that conversion before Guest code sees it.
     */
    private static Object launcherServiceActivity(GuestIdentity identity) {
        ActivityInfo activity = launcherActivityInfo(identity);
        try {
            HiddenApiAccess.ensureExemptions();
            Class<?> incrementalType = Class.forName("android.content.pm.IncrementalStatesInfo");
            Object incremental = construct(incrementalType,
                    new Class<?>[]{boolean.class, float.class, long.class},
                    new Object[]{false, 1.0f, 0L});
            if (incremental == null) {
                incremental = construct(incrementalType,
                        new Class<?>[]{boolean.class, float.class}, new Object[]{false, 1.0f});
            }
            Class<?> internalType = Class.forName("android.content.pm.LauncherActivityInfoInternal");
            Object internal = construct(internalType,
                    new Class<?>[]{ActivityInfo.class, incrementalType, UserHandle.class},
                    new Object[]{activity, incremental,
                            userHandle(UserHandle.class, identity.virtualUserId())});
            if (internal == null) {
                internal = construct(internalType,
                    new Class<?>[]{ActivityInfo.class, incrementalType},
                    new Object[]{activity, incremental});
            }
            if (incremental != null && internal != null) return internal;
            throw unsupported("LAUNCHER_ACTIVITY_INTERNAL", internalType);
        } catch (ClassNotFoundException legacyPlatform) {
            ResolveInfo resolved = new ResolveInfo();
            resolved.activityInfo = activity;
            return resolved;
        }
    }

    private static ActivityInfo launcherActivityInfo(GuestIdentity identity) {
        String launcher = identity.packageMetadata().launcherActivity();
        ComponentInfo component = identity.packageMetadata().componentInfo(
                new ComponentName(identity.packageName(), launcher),
                VirtualPackageMetadata.Type.ACTIVITY);
        if (component instanceof ActivityInfo activity) return new ActivityInfo(activity);
        ActivityInfo activity = new ActivityInfo();
        activity.packageName = identity.packageName();
        activity.name = launcher;
        activity.applicationInfo = identity.applicationInfo();
        activity.enabled = true;
        return activity;
    }

    static Object shortcut(Class<?> type, VirtualShortcutSnapshot snapshot, GuestIdentity identity) {
        if (type == Object.class) return new ProjectedShortcut(snapshot.id(), identity.packageName(),
                snapshot.activityClass(), snapshot.shortLabel(), snapshot.longLabel(), snapshot.rank(),
                snapshot.enabled(), snapshot.dynamic(), snapshot.pinned(), snapshot.manifest(),
                snapshot.longLived(), snapshot.usageCount());
        Object value = construct(type, new Class<?>[0], new Object[0]);
        if (value == null) throw unsupported("SHORTCUT_INFO", type);
        write(value, snapshot.id(), "mId", "id");
        write(value, identity.packageName(), "mPackageName", "packageName");
        write(value, snapshot.activityClass(), "mActivity", "activity", "activityClass");
        write(value, snapshot.shortLabel(), "mTitle", "mShortLabel", "shortLabel");
        write(value, snapshot.longLabel(), "mText", "mLongLabel", "longLabel");
        write(value, snapshot.disabledMessage(), "mDisabledMessage", "disabledMessage");
        write(value, snapshot.rank(), "mRank", "rank");
        write(value, snapshot.lastChangedMs(), "mLastChangedTimestamp", "lastChangedTimestamp");
        int flags = (snapshot.dynamic() ? 1 : 0) | (snapshot.pinned() ? 2 : 0)
                | (snapshot.manifest() ? 32 : 0) | (snapshot.longLived() ? 8192 : 0)
                | (!snapshot.enabled() ? 64 : 0);
        write(value, flags, "mFlags", "flags");
        return value;
    }

    static VirtualShortcutSnapshot shortcutSnapshot(Object source, GuestIdentity identity, boolean dynamic) {
        if (source instanceof VirtualShortcutSnapshot value) return value;
        String id = text(read(source, "mId", "id", "getId"));
        String activity = text(read(source, "mActivity", "activity", "getActivity"));
        String shortLabel = text(read(source, "mShortLabel", "mTitle", "shortLabel", "getShortLabel"));
        String longLabel = text(read(source, "mLongLabel", "mText", "longLabel", "getLongLabel"));
        String disabledMessage = text(read(source, "mDisabledMessage", "disabledMessage", "getDisabledMessage"));
        int rank = integer(read(source, "mRank", "rank", "getRank"), 0);
        // ShortcutInfo.Builder uses the framework sentinel RANK_NOT_SET until the service
        // assigns a rank. The virtual contract persists only concrete bounded ranks.
        if (rank < 0 || rank > 10_000) rank = 0;
        boolean enabled = bool(read(source, "isEnabled", "enabled"), true);
        boolean pinned = bool(read(source, "isPinned", "pinned"), false);
        boolean manifest = bool(read(source, "isDeclaredInManifest", "manifest"), false);
        boolean longLived = bool(read(source, "isLongLived", "longLived"), false);
        long changed = number(read(source, "mLastChangedTimestamp", "lastChangedTimestamp"),
                System.currentTimeMillis());
        if (id.isBlank()) throw new IllegalArgumentException("VIRTUAL_SHORTCUT_ID_REQUIRED");
        if (shortLabel.isBlank()) shortLabel = id;
        return new VirtualShortcutSnapshot(id, activity, shortLabel, longLabel, disabledMessage,
                List.of(), rank, enabled, dynamic, pinned, manifest, longLived, changed, 0);
    }

    static Object appWidgetInfo(Class<?> type, VirtualWidgetSnapshot snapshot) {
        if (type == Object.class) return new ProjectedAppWidget(snapshot.appWidgetId(), snapshot.hostId(),
                snapshot.providerPackage(), snapshot.providerClass(), snapshot.bound(), snapshot.updatedAtMs());
        Object value = construct(type, new Class<?>[0], new Object[0]);
        if (value == null) throw unsupported("APP_WIDGET_INFO", type);
        write(value, snapshot.appWidgetId(), "appWidgetId", "mAppWidgetId");
        write(value, snapshot.hostId(), "hostId", "mHostId");
        write(value, snapshot.providerPackage(), "providerPackage", "mProviderPackage");
        write(value, snapshot.providerClass(), "providerClass", "mProviderClass");
        return value;
    }

    static Object usageEvent(Class<?> type, VirtualUsageEventSnapshot event) {
        if (type == Object.class) return new ProjectedUsageEvent(event.timestampMs(), event.eventType(),
                event.packageName(), event.className(), event.taskRootPackage(), event.shortcutId(), event.instanceId());
        Object value = construct(type, new Class<?>[0], new Object[0]);
        if (value == null) throw unsupported("USAGE_EVENT", type);
        write(value, event.timestampMs(), "mTimeStamp", "timestamp", "timeStamp");
        write(value, event.eventType(), "mEventType", "eventType");
        write(value, event.packageName(), "mPackage", "packageName");
        write(value, event.className(), "mClass", "className");
        write(value, event.taskRootPackage(), "mTaskRootPackage", "taskRootPackage");
        write(value, event.shortcutId(), "mShortcutId", "shortcutId");
        write(value, event.instanceId(), "mInstanceId", "instanceId");
        return value;
    }

    static Object collectionResult(Class<?> returnType, List<?> source, ElementFactory factory) {
        Class<?> elementType = Object.class;
        List<Object> projected = new ArrayList<>();
        for (Object value : source) projected.add(factory.create(elementType, value));
        if (returnType.isArray()) {
            Class<?> component = returnType.getComponentType();
            Object array = Array.newInstance(component, source.size());
            for (int index = 0; index < source.size(); index++) Array.set(array, index,
                    factory.create(component, source.get(index)));
            return array;
        }
        if (List.class.isAssignableFrom(returnType) || returnType == Object.class) {
            return Collections.unmodifiableList(projected);
        }
        Object value = construct(returnType, new Class<?>[]{List.class}, new Object[]{projected});
        if (value != null) return value;
        value = construct(returnType, new Class<?>[0], new Object[0]);
        if (value != null) {
            write(value, projected, "mList", "list", "mItems", "items");
            return value;
        }
        throw unsupported("COLLECTION", returnType);
    }

    static Object shortcutCollectionResult(Class<?> returnType, List<?> source,
            ElementFactory factory) {
        if (!isAndroidFuture(returnType)) return collectionResult(returnType, source, factory);
        try {
            Class<?> sliceType = Class.forName("android.content.pm.ParceledListSlice");
            return collectionResult(sliceType, source, factory);
        } catch (ClassNotFoundException unavailable) {
            throw new IllegalStateException("VIRTUAL_SHORTCUT_SLICE_UNAVAILABLE", unavailable);
        }
    }

    static boolean isAndroidFuture(Class<?> type) {
        return type != null && "com.android.internal.infra.AndroidFuture".equals(type.getName());
    }

    static Object completedFuture(Class<?> futureType, Object result) {
        HiddenApiAccess.ensureExemptions();
        try {
            for (Method factory : futureType.getDeclaredMethods()) {
                if (!"completedFuture".equals(factory.getName()) || factory.getParameterCount() != 1) continue;
                factory.setAccessible(true);
                Object completed = factory.invoke(null, result);
                if (futureType.isInstance(completed)) return completed;
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) { }
        Object future = construct(futureType, new Class<?>[0], new Object[0]);
        if (future == null) throw unsupported("ANDROID_FUTURE", futureType);
        try {
            Method complete = futureType.getMethod("complete", Object.class);
            complete.invoke(future, result);
            return future;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("VIRTUAL_ANDROID_FUTURE_COMPLETION_FAILED", failure);
        }
    }

    static Object launcherActivities(Class<?> returnType, GuestIdentity identity) {
        Object activity = "android.content.pm.ParceledListSlice".equals(returnType.getName())
                ? launcherServiceActivity(identity) : launcherActivity(Object.class, identity);
        List<?> values = activity == null ? List.of() : List.of(activity);
        return collectionResult(returnType, values, (type, value) -> type == Object.class
                ? value : launcherActivity(type, identity));
    }

    private static Object construct(Class<?> type, Class<?>[] parameterTypes, Object[] values) {
        if (type == null || type == void.class || type == Void.class) return null;
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        } catch (Throwable ignored) { return null; }
    }

    private static Object read(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            Class<?> cursor = target.getClass();
            while (cursor != null) {
                try {
                    Field field = cursor.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) { cursor = cursor.getSuperclass(); }
            }
            try {
                Method method = target.getClass().getMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void write(Object target, Object value, String... names) {
        if (target == null) return;
        for (String name : names) {
            Class<?> cursor = target.getClass();
            while (cursor != null) {
                try {
                    Field field = cursor.getDeclaredField(name);
                    field.setAccessible(true);
                    Object converted = convert(value, field.getType());
                    if (converted != Unset.VALUE) field.set(target, converted);
                    return;
                } catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
                catch (Throwable ignored) { return; }
            }
        }
    }

    private static Class<?> fieldType(Object target, String... names) {
        if (target == null) return Object.class;
        for (String name : names) {
            Class<?> cursor = target.getClass();
            while (cursor != null) {
                try { return cursor.getDeclaredField(name).getType(); }
                catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
            }
        }
        return Object.class;
    }

    private static Object convert(Object value, Class<?> type) {
        if (value == null) return type.isPrimitive() ? Unset.VALUE : null;
        if (type.isInstance(value) || type == Object.class) return value;
        if (type == String.class) return text(value);
        if (type == int.class || type == Integer.class) return integer(value, 0);
        if (type == long.class || type == Long.class) return number(value, 0L);
        if (type == boolean.class || type == Boolean.class) return bool(value, false);
        return Unset.VALUE;
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof CharSequence sequence) return sequence.toString();
        try {
            Method flatten = value.getClass().getMethod("flattenToString");
            Object result = flatten.invoke(value);
            if (result != null) return String.valueOf(result);
        } catch (Throwable ignored) { }
        return String.valueOf(value);
    }
    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }
    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }
    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }
    private static IllegalStateException unsupported(String kind, Class<?> type) {
        return new IllegalStateException("VIRTUAL_" + kind + "_PROJECTION_UNSUPPORTED:"
                + (type == null ? "null" : type.getName()));
    }

    interface ElementFactory { Object create(Class<?> type, Object value); }
    private enum Unset { VALUE }
    record ProjectedUserHandle(int identifier) { }
    record ProjectedUserInfo(int id, long serialNumber, String name, int flags,
                             boolean running, boolean unlocked, boolean quietMode) { }
    record ProjectedLauncherActivity(String packageName, String className, int userId, boolean enabled) { }
    record ProjectedShortcut(String id, String packageName, String activityClass, String shortLabel,
                             String longLabel, int rank, boolean enabled, boolean dynamic,
                             boolean pinned, boolean manifest, boolean longLived, int usageCount) { }
    record ProjectedAppWidget(int appWidgetId, int hostId, String providerPackage,
                              String providerClass, boolean bound, long updatedAtMs) { }
    record ProjectedUsageEvent(long timestampMs, int eventType, String packageName,
                               String className, String taskRootPackage, String shortcutId, int instanceId) { }
}
