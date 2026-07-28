package com.warden.controlledsandbox.runtime.guest;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import com.warden.controlledsandbox.contract.ActivityTaskSnapshot;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Centralized reflection projection for version-varying Android TaskInfo/AppTask classes. */
final class AndroidTaskInfoProjector {
    private static final String RUNNING_INFO = "android.app.ActivityManager$RunningTaskInfo";
    private static final String RECENT_INFO = "android.app.ActivityManager$RecentTaskInfo";
    private static final String I_APP_TASK = "android.app.IAppTask";
    private static final String PARCELED_LIST_SLICE = "android.content.pm.ParceledListSlice";

    private AndroidTaskInfoProjector() { }

    static Object running(List<ActivityTaskSnapshot> tasks, Method frameworkMethod) {
        return wrap(project(tasks, RUNNING_INFO), frameworkMethod.getReturnType());
    }

    static Object recent(List<ActivityTaskSnapshot> tasks, Method frameworkMethod) {
        return wrap(project(tasks, RECENT_INFO), frameworkMethod.getReturnType());
    }

    static Object appTasks(List<ActivityTaskSnapshot> tasks, Method frameworkMethod,
                           AppTaskOperations operations) {
        Objects.requireNonNull(operations, "operations");
        try {
            ClassLoader loader = frameworkMethod.getDeclaringClass().getClassLoader();
            Class<?> appTaskInterface = load(I_APP_TASK, loader);
            List<Object> projected = new ArrayList<>(tasks.size());
            for (ActivityTaskSnapshot task : tasks) {
                Object recent = projectOne(task, RECENT_INFO, loader);
                Binder binder = new Binder();
                Object remote = Proxy.newProxyInstance(
                        loader == null ? AndroidTaskInfoProjector.class.getClassLoader() : loader,
                        new Class<?>[]{appTaskInterface},
                        (proxy, method, args) -> invokeAppTask(task, recent, operations, binder, method));
                if (!(remote instanceof IInterface localInterface)) {
                    throw new IllegalStateException("GUEST_APP_TASK_INTERFACE_INVALID");
                }
                binder.attachInterface(localInterface, I_APP_TASK);
                projected.add(binder);
            }
            return wrap(projected, frameworkMethod.getReturnType());
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IllegalStateException("GUEST_APP_TASK_PROJECTION_UNAVAILABLE", error);
        }
    }

    private static Object invokeAppTask(ActivityTaskSnapshot task, Object recent,
                                        AppTaskOperations operations, IBinder binder, Method method) {
        return switch (method.getName()) {
            case "asBinder" -> binder;
            case "getTaskInfo" -> recent;
            case "moveToFront" -> {
                operations.moveToFront(task.taskId());
                yield defaultValue(method.getReturnType(), true);
            }
            case "finishAndRemoveTask" -> {
                operations.finishAndRemoveTask(task.taskId());
                yield defaultValue(method.getReturnType(), true);
            }
            case "toString" -> "GuestAppTask[" + task.taskId() + "]";
            case "hashCode" -> task.taskId();
            case "equals" -> false;
            default -> throw new UnsupportedOperationException(
                    "GUEST_APP_TASK_METHOD_UNSUPPORTED:" + method.getName());
        };
    }

    private static List<Object> project(List<ActivityTaskSnapshot> tasks, String className) {
        try {
            ClassLoader loader = AndroidTaskInfoProjector.class.getClassLoader();
            List<Object> projected = new ArrayList<>(tasks.size());
            for (ActivityTaskSnapshot task : tasks) projected.add(projectOne(task, className, loader));
            return projected;
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IllegalStateException("VIRTUAL_TASK_PROJECTION_UNAVAILABLE:" + className, error);
        }
    }

    private static Object projectOne(ActivityTaskSnapshot task, String className,
                                     ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = load(className, loader);
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object value = constructor.newInstance();
        boolean taskIdSet = setAny(value, task.taskId(), "taskId", "id", "persistentId");
        if (!taskIdSet) throw new NoSuchFieldException(className + ".taskId/id/persistentId");
        setIfPresent(value, task.virtualUserId(), "userId");
        setIfPresent(value, task.activityCount(), "numActivities");
        setIfPresent(value, task.lastActiveSequence(), "lastActiveTime");
        setIfPresent(value, task.active(), "isRunning");
        setIfPresent(value, component(task.packageName(), task.baseComponentName()),
                "baseActivity", "origActivity", "realActivity");
        setIfPresent(value, component(task.packageName(), task.topComponentName()), "topActivity");
        Intent baseIntent = new Intent();
        ComponentName base = component(task.packageName(), task.baseComponentName());
        if (base != null) baseIntent.setComponent(base);
        setIfPresent(value, baseIntent, "baseIntent");
        setIfPresent(value, task.excludedFromRecents(), "isExcluded");
        return value;
    }

    private static Object wrap(List<Object> values, Class<?> returnType) {
        if (returnType.isAssignableFrom(ArrayList.class)
                || returnType.isAssignableFrom(List.class)
                || List.class.isAssignableFrom(returnType)
                || returnType == Object.class) {
            return values;
        }
        if (PARCELED_LIST_SLICE.equals(returnType.getName())) {
            try {
                Constructor<?> constructor = returnType.getDeclaredConstructor(List.class);
                constructor.setAccessible(true);
                return constructor.newInstance(values);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("VIRTUAL_TASK_LIST_PROJECTION_UNAVAILABLE", error);
            }
        }
        throw new IllegalStateException("VIRTUAL_TASK_RETURN_TYPE_UNSUPPORTED:" + returnType.getName());
    }


    private static Class<?> load(String name, ClassLoader preferred) throws ClassNotFoundException {
        ClassLoader loader = preferred == null ? AndroidTaskInfoProjector.class.getClassLoader() : preferred;
        return Class.forName(name, false, loader);
    }

    private static ComponentName component(String packageName, String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        String packagePart = packageName;
        String classPart = encoded.trim();
        int separator = classPart.indexOf('/');
        if (separator >= 0) {
            packagePart = classPart.substring(0, separator);
            classPart = classPart.substring(separator + 1);
        }
        if (classPart.startsWith(".")) classPart = packagePart + classPart;
        return new ComponentName(packagePart, classPart);
    }

    private static boolean setAny(Object target, Object value, String... names)
            throws IllegalAccessException {
        boolean applied = false;
        for (String name : names) applied |= set(target, name, value);
        return applied;
    }

    private static void setIfPresent(Object target, Object value, String... names)
            throws IllegalAccessException {
        for (String name : names) set(target, name, value);
    }

    private static boolean set(Object target, String name, Object value) throws IllegalAccessException {
        Field field = findField(target.getClass(), name);
        if (field == null) return false;
        if (value == null && field.getType().isPrimitive()) return false;
        field.setAccessible(true);
        field.set(target, value);
        return true;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        return null;
    }

    interface AppTaskOperations {
        void moveToFront(int taskId);
        void finishAndRemoveTask(int taskId);
    }

    private static Object defaultValue(Class<?> type, boolean booleanValue) {
        if (type == void.class) return null;
        if (type == boolean.class || type == Boolean.class) return booleanValue;
        if (type == int.class || type == Integer.class) return booleanValue ? 1 : 0;
        if (type == long.class || type == Long.class) return booleanValue ? 1L : 0L;
        return null;
    }
}
