package com.warden.controlledsandbox.framework.identity;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Rewrites only exact Guest identity values; unrelated values are preserved. */
public final class IdentityArgumentRewriter {
    private static final String ATTRIBUTION_SOURCE = "android.content.AttributionSource";
    private static final String ATTRIBUTION_SOURCE_STATE = "android.content.AttributionSourceState";

    private final IdentityContext context;

    public IdentityArgumentRewriter(IdentityContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public Object[] rewriteInbound(Object[] arguments, MethodIdentityPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (arguments == null || arguments.length == 0) {
            return arguments;
        }
        if (arguments.length != policy.argumentCount()) {
            throw new IdentityRewriteException(
                    "Argument count mismatch for " + policy.methodName()
                            + ": expected=" + policy.argumentCount()
                            + " actual=" + arguments.length);
        }
        Object[] copy = arguments.clone();
        Map<Object, Object> visited = new IdentityHashMap<>();
        for (ArgumentRewriteRule rule : policy.rules()) {
            int index = rule.index();
            copy[index] = rewriteRuleValue(copy[index], rule.kind(), visited);
        }
        return copy;
    }

    public Object rewriteOutbound(Object value) {
        return rewriteOutboundValue(value, new IdentityHashMap<>());
    }

    private Object rewriteRuleValue(
            Object value,
            IdentityValueKind kind,
            Map<Object, Object> visited) {
        if (value == null) {
            return null;
        }
        return switch (kind) {
            case PACKAGE_NAME -> rewritePackageName(value);
            case UID -> rewriteUid(value);
            case PACKAGE_NAME_ARRAY -> rewritePackageNameCollection(value, visited);
            case ATTRIBUTION_SOURCE -> rewriteRequiredAttributionSource(value, visited);
        };
    }

    private Object rewritePackageName(Object value) {
        if (!(value instanceof String text)) {
            throw new IdentityRewriteException(
                    "Expected package String but found " + value.getClass().getName());
        }
        return text.equals(context.guestPackage()) ? context.hostPackage() : text;
    }

    private Object rewriteUid(Object value) {
        if (!(value instanceof Integer number)) {
            throw new IdentityRewriteException(
                    "Expected UID Integer but found " + value.getClass().getName());
        }
        return number == context.guestUid() ? context.hostUid() : number;
    }

    private Object rewritePackageNameCollection(Object value, Map<Object, Object> visited) {
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object copy = Array.newInstance(value.getClass().getComponentType(), length);
            visited.put(value, copy);
            for (int index = 0; index < length; index++) {
                Object item = Array.get(value, index);
                Array.set(copy, index, item == null ? null : rewritePackageName(item));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> rewritten = new ArrayList<>(list.size());
            for (Object item : list) {
                rewritten.add(item == null ? null : rewritePackageName(item));
            }
            return Collections.unmodifiableList(rewritten);
        }
        throw new IdentityRewriteException(
                "Expected package array/list but found " + value.getClass().getName());
    }

    private Object rewriteRequiredAttributionSource(Object value, Map<Object, Object> visited) {
        if (!ATTRIBUTION_SOURCE.equals(value.getClass().getName())) {
            throw new IdentityRewriteException(
                    "Expected AttributionSource but found " + value.getClass().getName());
        }
        return rewriteAttributionSource(value, visited);
    }

    private Object rewriteInboundValue(Object value, Map<Object, Object> visited) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text.equals(context.guestPackage()) ? context.hostPackage() : text;
        }
        if (value instanceof Integer number) {
            return number == context.guestUid() ? context.hostUid() : number;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            return rewriteArray(value, visited, true);
        }
        if (value instanceof List<?> list) {
            return rewriteList(list, visited, true);
        }
        if (ATTRIBUTION_SOURCE.equals(type.getName())) {
            return rewriteAttributionSource(value, visited);
        }
        if (ATTRIBUTION_SOURCE_STATE.equals(type.getName())) {
            return cloneAttributionState(value, visited);
        }
        return value;
    }

    private Object rewriteOutboundValue(Object value, Map<Object, Object> visited) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return rewriteOutboundString(text, false);
        }
        if (value instanceof Integer number) {
            return number == context.hostUid() ? context.guestUid() : number;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            return rewriteArray(value, visited, false);
        }
        if (value instanceof List<?> list) {
            return rewriteList(list, visited, false);
        }
        if (processNameField(type) != null) {
            return rewriteProcessIdentityRecord(value, visited, false);
        }
        return value;
    }

    /**
     * Binder out-params such as {@code getMyMemoryState} are filled in place. The caller
     * already holds this instance, so the Host slot process name must be overwritten here.
     */
    public void rewriteOutboundInPlace(Object value) {
        if (value == null || processNameField(value.getClass()) == null) return;
        rewriteProcessIdentityRecord(value, new IdentityHashMap<>(), true);
    }

    private String rewriteOutboundString(String text, boolean currentProcess) {
        if (text.equals(context.hostPackage())) {
            return currentProcess ? context.guestProcess() : context.guestPackage();
        }
        if (text.startsWith(context.hostPackage() + ":")) {
            return currentProcess
                    ? context.guestProcess()
                    : context.guestPackage() + text.substring(context.hostPackage().length());
        }
        return text;
    }

    private Object rewriteList(List<?> list, Map<Object, Object> visited, boolean inbound) {
        Object existing = visited.get(list);
        if (existing != null) {
            return existing;
        }
        ArrayList<Object> rewritten = new ArrayList<>(list.size());
        visited.put(list, rewritten);
        for (Object item : list) {
            if (!inbound && item != null && shouldHideHostSlotProcess(item)) {
                continue;
            }
            rewritten.add(inbound
                    ? rewriteInboundValue(item, visited)
                    : rewriteOutboundValue(item, visited));
        }
        List<Object> immutable = Collections.unmodifiableList(rewritten);
        visited.put(list, immutable);
        return immutable;
    }

    private Object rewriteArray(Object array, Map<Object, Object> visited, boolean inbound) {
        Object existing = visited.get(array);
        if (existing != null) {
            return existing;
        }
        int length = Array.getLength(array);
        Class<?> componentType = array.getClass().getComponentType();
        Object copy = Array.newInstance(componentType, length);
        visited.put(array, copy);
        for (int index = 0; index < length; index++) {
            Object item = Array.get(array, index);
            Object rewritten = inbound
                    ? rewriteInboundValue(item, visited)
                    : rewriteOutboundValue(item, visited);
            Array.set(copy, index, rewritten);
        }
        return copy;
    }

    private Object rewriteAttributionSource(Object original, Map<Object, Object> visited) {
        Object existing = visited.get(original);
        if (existing != null) {
            return existing;
        }
        try {
            Object originalState = readField(original, "mAttributionSourceState");
            if (originalState == null) {
                throw new IdentityRewriteException("AttributionSource state is null");
            }
            Object stateCopy = cloneAttributionState(originalState, visited);
            Constructor<?> stateConstructor = findCompatibleConstructor(original.getClass(), stateCopy.getClass());
            stateConstructor.setAccessible(true);
            Object copy = stateConstructor.newInstance(stateCopy);
            visited.put(original, copy);
            return copy;
        } catch (IdentityRewriteException exception) {
            throw exception;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IdentityRewriteException("Unable to reconstruct AttributionSource", exception);
        }
    }

    private Object cloneAttributionState(Object originalState, Map<Object, Object> visited) {
        Object existing = visited.get(originalState);
        if (existing != null) {
            return existing;
        }
        try {
            Class<?> stateType = originalState.getClass();
            Constructor<?> constructor = stateType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object stateCopy = constructor.newInstance();
            visited.put(originalState, stateCopy);

            for (Field field : allInstanceFields(stateType)) {
                field.setAccessible(true);
                Object originalValue = field.get(originalState);
                Object copiedValue = copyAttributionStateField(field.getName(), originalValue, visited);
                setFieldValue(field, stateCopy, copiedValue);
            }
            return stateCopy;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IdentityRewriteException("Unable to clone AttributionSourceState", exception);
        }
    }

    private Object copyAttributionStateField(
            String fieldName,
            Object originalValue,
            Map<Object, Object> visited) {
        if ("packageName".equals(fieldName)
                && Objects.equals(originalValue, context.guestPackage())) {
            return context.hostPackage();
        }
        if ("uid".equals(fieldName)
                && Objects.equals(originalValue, context.guestUid())) {
            return context.hostUid();
        }
        if (originalValue == null) {
            return null;
        }
        Class<?> valueType = originalValue.getClass();
        if ("next".equals(fieldName) && valueType.isArray()) {
            int length = Array.getLength(originalValue);
            Object copy = Array.newInstance(valueType.getComponentType(), length);
            visited.put(originalValue, copy);
            for (int index = 0; index < length; index++) {
                Object nextState = Array.get(originalValue, index);
                Array.set(copy, index, nextState == null ? null : cloneAttributionState(nextState, visited));
            }
            return copy;
        }
        if (ATTRIBUTION_SOURCE_STATE.equals(valueType.getName())) {
            return cloneAttributionState(originalValue, visited);
        }
        return originalValue;
    }

    private Object rewriteProcessIdentityRecord(Object value, Map<Object, Object> visited,
                                                boolean inPlace) {
        Object existing = visited.get(value);
        if (existing != null) return existing;
        Object target = inPlace ? value : copyProcessIdentityRecord(value);
        if (target == null) return value;
        visited.put(value, target);
        try {
            Field processName = processNameField(target.getClass());
            Field pid = findOptionalField(target.getClass(), "pid");
            Field pkgList = findOptionalField(target.getClass(), "pkgList");
            int recordPid = pid == null ? -1 : pid.getInt(target);
            boolean current = recordPid == android.os.Process.myPid() || recordPid <= 0;
            if (processName != null) {
                Object name = processName.get(target);
                if (name instanceof String text) {
                    processName.set(target, rewriteOutboundString(text, current));
                }
            }
            if (pkgList != null) {
                pkgList.set(target, rewriteOutboundValue(pkgList.get(target), visited));
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return value;
        }
        return target;
    }

    private boolean shouldHideHostSlotProcess(Object value) {
        Field processName = processNameField(value.getClass());
        if (processName == null) return false;
        try {
            Object name = processName.get(value);
            if (!(name instanceof String text)) return false;
            if (!text.startsWith(context.hostPackage() + ":")) return false;
            Field pid = findOptionalField(value.getClass(), "pid");
            int recordPid = pid == null ? -1 : pid.getInt(value);
            return recordPid != android.os.Process.myPid();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static Object copyProcessIdentityRecord(Object value) {
        try {
            Constructor<?> constructor = value.getClass().getDeclaredConstructor();
            constructor.setAccessible(true);
            Object copy = constructor.newInstance();
            for (Field field : allInstanceFields(value.getClass())) {
                field.setAccessible(true);
                setFieldValue(field, copy, field.get(value));
            }
            return copy;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field processNameField(Class<?> type) {
        Field field = findOptionalField(type, "processName");
        if (field == null || field.getType() != String.class) return null;
        try {
            field.setAccessible(true);
            return field;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Field findOptionalField(Class<?> type, String name) {
        try {
            Field field = findField(type, name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<Field> allInstanceFields(Class<?> type) {
        ArrayList<Field> fields = new ArrayList<>();
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            for (Field field : cursor.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    fields.add(field);
                }
            }
            cursor = cursor.getSuperclass();
        }
        return fields;
    }

    private static Constructor<?> findCompatibleConstructor(Class<?> type, Class<?> argumentType)
            throws NoSuchMethodException {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 1 && parameterTypes[0].isAssignableFrom(argumentType)) {
                return constructor;
            }
        }
        throw new NoSuchMethodException(type.getName() + "(" + argumentType.getName() + ")");
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setFieldValue(Field field, Object target, Object value) throws IllegalAccessException {
        clearFinal(field);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static void clearFinal(Field field) {
        if (!Modifier.isFinal(field.getModifiers())) {
            return;
        }
        try {
            Field modifiers = Field.class.getDeclaredField("modifiers");
            modifiers.setAccessible(true);
            modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (ReflectiveOperationException ignored) {
            // Android may allow setting an accessible final instance field directly.
        }
    }
}
