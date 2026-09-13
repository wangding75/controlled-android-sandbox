package com.warden.controlledsandbox.framework.identity;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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

    /** Exposes the immutable installation context to the shared Binder identity layer. */
    public IdentityContext context() {
        return context;
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
        boolean liveProcessList = !inbound && containsProcessIdentityRecord(list);
        List<?> source = inbound ? list : appendIndexedGuestProcesses(list);
        ArrayList<Object> rewritten = new ArrayList<>(source.size());
        visited.put(list, rewritten);
        for (Object item : source) {
            if (!inbound && item != null && shouldHideHostSlotProcess(item)) {
                continue;
            }
            rewritten.add(inbound
                    ? rewriteInboundValue(item, visited)
                    : rewriteOutboundValue(item, visited));
        }
        List<Object> immutable = Collections.unmodifiableList(rewritten);
        if (!liveProcessList) {
            visited.put(list, immutable);
            return immutable;
        }
        // ActivityManager caches the Binder result on recent Android releases. The virtual
        // renderer can be bound after that first query, so a one-shot projection leaves the
        // cached List permanently without the renderer ProcessRecord. Keep the returned list
        // live, but only for a top-level process-discovery result; ordinary framework lists stay
        // immutable and retain the existing identity semantics.
        LiveProcessIdentityList live = new LiveProcessIdentityList(list, immutable);
        visited.put(list, live);
        return live;
    }

    private boolean containsProcessIdentityRecord(List<?> list) {
        for (Object item : list) {
            if (item != null && isProcessIdentityRecord(item)) return true;
        }
        return false;
    }

    private final class LiveProcessIdentityList extends AbstractList<Object> {
        private final List<?> source;
        private volatile List<Object> current;

        LiveProcessIdentityList(List<?> source, List<Object> initial) {
            this.source = source;
            this.current = initial;
        }

        @Override public Object get(int index) {
            refresh();
            return current.get(index);
        }

        @Override public int size() {
            refresh();
            return current.size();
        }

        @Override public Object set(int index, Object element) {
            throw new UnsupportedOperationException("running process projection is read-only");
        }

        @Override public void add(int index, Object element) {
            throw new UnsupportedOperationException("running process projection is read-only");
        }

        @Override public Object remove(int index) {
            throw new UnsupportedOperationException("running process projection is read-only");
        }

        private synchronized void refresh() {
            List<?> snapshot = appendIndexedGuestProcesses(source);
            ArrayList<Object> projected = new ArrayList<>(snapshot.size());
            for (Object item : snapshot) {
                if (item != null && shouldHideHostSlotProcess(item)) continue;
                projected.add(rewriteOutboundValue(item, new IdentityHashMap<>()));
            }
            current = Collections.unmodifiableList(projected);
        }
    }

    /**
     * NBB has a virtual ProcessRecord before the host AMS publishes the corresponding physical
     * process in getRunningAppProcesses(). CAS can only rewrite records returned by AMS, so the
     * first renderer bind otherwise reaches Chromium with pid 0. The pid index is written by the
     * Guest process before its service is exposed; use a live indexed PID to fill that short
     * publication gap, copying the platform record shape rather than inventing a new type.
     */
    private List<?> appendIndexedGuestProcesses(List<?> list) {
        android.util.Log.i("CS_GUEST_PROCESS_IDENTITY", "AMS_PROCESS_LIST_SCAN size=" + list.size()
                + " first=" + (list.isEmpty() || list.get(0) == null
                ? "null" : list.get(0).getClass().getName()));
        Object template = null;
        Field templatePid = null;
        Field templateProcessName = null;
        HashSet<Integer> knownPids = new HashSet<>();
        HashSet<String> knownNames = new HashSet<>();
        StringBuilder observed = new StringBuilder();
        for (Object item : list) {
            if (item == null || !isProcessIdentityRecord(item)) continue;
            if (template == null) {
                template = item;
                templatePid = findOptionalField(item.getClass(), "pid");
                templateProcessName = processNameField(item.getClass());
            }
            try {
                int pid = templatePidFor(item);
                if (pid > 0) knownPids.add(pid);
                Field processName = processNameField(item.getClass());
                Object name = processName == null ? null : processName.get(item);
                if (name instanceof String text && !text.isEmpty()) knownNames.add(text);
                observed.append(pid).append('=').append(name).append(" uid=")
                        .append(processUid(item)).append(';');
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // An OEM process-record shape may be partially inaccessible; the normal rewrite
                // below remains best-effort for that record.
            }
        }
        android.util.Log.i("CS_GUEST_PROCESS_IDENTITY", "AMS_PROCESS_LIST_RECORDS " + observed);
        if (template == null || templatePid == null || templateProcessName == null) return list;

        Map<String, IndexedGuestProcess> indexedProcesses = new HashMap<>();
        for (java.io.File directory : guestPidIndexDirectories()) {
            java.io.File[] files = directory.listFiles();
            android.util.Log.i("CS_GUEST_PROCESS_IDENTITY", "AMS_PROCESS_LIST_INDEX dir="
                    + directory + " exists=" + directory.isDirectory() + " files="
                    + (files == null ? -1 : files.length));
            if (files == null) continue;
            for (java.io.File file : files) {
                int pid = parsePid(file);
                if (pid <= 0 || knownPids.contains(pid) || !isLiveProcess(pid)) continue;
                String processName = readGuestProcessName(file);
                if (!isGuestProcessName(processName)) continue;
                IndexedGuestProcess candidate = new IndexedGuestProcess(
                        pid, processName, file.lastModified());
                IndexedGuestProcess previous = indexedProcesses.get(processName);
                if (previous == null || candidate.isNewerThan(previous)) {
                    indexedProcesses.put(processName, candidate);
                }
            }
        }

        ArrayList<Object> expanded = null;
        for (IndexedGuestProcess candidate : indexedProcesses.values()) {
                int pid = candidate.pid();
                String processName = candidate.processName();
                if (knownNames.contains(processName)) continue;
                Object copy = copyProcessIdentityRecord(template);
                if (copy == null) continue;
                try {
                    Field pidField = findOptionalField(copy.getClass(), "pid");
                    Field processNameField = processNameField(copy.getClass());
                    if (pidField == null || processNameField == null) continue;
                    pidField.setInt(copy, pid);
                    processNameField.set(copy, processName);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    continue;
                }
                if (expanded == null) expanded = new ArrayList<>(list);
                expanded.add(copy);
                knownPids.add(pid);
                knownNames.add(processName);
                android.util.Log.i("CS_GUEST_PROCESS_IDENTITY", "pid index projected pid=" + pid
                        + " process=" + processName);
        }
        return expanded == null ? list : expanded;
    }

    private boolean isProcessIdentityRecord(Object value) {
        return processNameField(value.getClass()) != null
                && findOptionalField(value.getClass(), "pid") != null;
    }

    private static int templatePidFor(Object value) throws IllegalAccessException {
        Field pid = findOptionalField(value.getClass(), "pid");
        return pid == null ? -1 : pid.getInt(value);
    }

    private boolean isGuestProcessName(String processName) {
        return processName != null
                && (processName.equals(context.guestPackage())
                || processName.startsWith(context.guestPackage() + ":"));
    }

    private java.io.File[] guestPidIndexDirectories() {
        String relative = "files/instances/u" + context.virtualUserId() + "/"
                + context.guestPackage() + "/data/files/guest-pid";
        return new java.io.File[] {
                new java.io.File("/data/data/" + context.hostPackage() + "/" + relative),
                new java.io.File("/data/user/" + context.virtualUserId() + "/"
                        + context.hostPackage() + "/" + relative),
                new java.io.File("/data/data/" + context.guestPackage() + "/files/guest-pid"),
                new java.io.File("/data/user/" + context.virtualUserId() + "/"
                        + context.guestPackage() + "/files/guest-pid")
        };
    }

    private static int parsePid(java.io.File file) {
        if (file == null || !file.isFile()) return -1;
        try {
            long value = Long.parseLong(file.getName());
            return value > 0L && value <= Integer.MAX_VALUE ? (int) value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private record IndexedGuestProcess(int pid, String processName, long modifiedAt) {
        private boolean isNewerThan(IndexedGuestProcess other) {
            return modifiedAt > other.modifiedAt
                    || (modifiedAt == other.modifiedAt && pid > other.pid);
        }
    }

    private static boolean isLiveProcess(int pid) {
        return new java.io.File("/proc/" + pid).isDirectory();
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
            Field uid = findOptionalField(target.getClass(), "uid");
            Field pkgList = findOptionalField(target.getClass(), "pkgList");
            int recordPid = pid == null ? -1 : pid.getInt(target);
            boolean current = recordPid == android.os.Process.myPid() || recordPid <= 0;
            if (processName != null) {
                Object name = processName.get(target);
                String indexedGuestName = current ? null : lookupGuestProcessName(recordPid);
                String projected = current
                        ? (name instanceof String text ? rewriteOutboundString(text, true)
                                : context.guestProcess())
                        : indexedGuestName;
                if (projected == null && name instanceof String text) {
                    projected = rewriteOutboundString(text, false);
                }
                if (projected != null) {
                    processName.set(target, projected);
                    if (uid != null && isGuestProcessName(projected)) {
                        uid.setInt(target, context.guestUid());
                    }
                    android.util.Log.i("CS_GUEST_PROCESS_IDENTITY", "AMS_PROCESS_RECORD pid="
                            + recordPid + " raw=" + name + " projected=" + processName.get(target)
                            + " uid=" + processUid(target));
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

    private static int processUid(Object value) {
        Field uid = findOptionalField(value.getClass(), "uid");
        if (uid == null) return -1;
        try {
            return uid.getInt(value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1;
        }
    }

    private boolean shouldHideHostSlotProcess(Object value) {
        Field processName = processNameField(value.getClass());
        if (processName == null) return false;
        try {
            Object name = processName.get(value);
            if (!(name instanceof String text)) return false;
            if (!text.startsWith(context.hostPackage() + ":")) return false;
            String suffix = text.substring(context.hostPackage().length() + 1);
            // Host internals. Guest renderer/GPU slots are :guestN and must remain visible so
            // Chromium can resolve SandboxedPrivilegedProcessService pid (NBB lists every
            // process of the caller package). Hiding them leaves pid:0/0 and SIGSEGV.
            if (suffix.startsWith("sandbox_")) return true;
            if (suffix.startsWith("guest")) return false;
            Field pid = findOptionalField(value.getClass(), "pid");
            int recordPid = pid == null ? -1 : pid.getInt(value);
            return recordPid != android.os.Process.myPid();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private String lookupGuestProcessName(int pid) {
        if (pid <= 0) return null;
        String relative = "files/instances/u" + context.virtualUserId() + "/"
                + context.guestPackage() + "/data/files/guest-pid/" + pid;
        java.io.File[] candidates = {
                new java.io.File("/data/data/" + context.hostPackage() + "/" + relative),
                new java.io.File("/data/user/" + context.virtualUserId() + "/"
                        + context.hostPackage() + "/" + relative),
                new java.io.File("/data/data/" + context.guestPackage()
                        + "/files/guest-pid/" + pid),
                new java.io.File("/data/user/" + context.virtualUserId() + "/"
                        + context.guestPackage() + "/files/guest-pid/" + pid)
        };
        for (java.io.File file : candidates) {
            String value = readGuestProcessName(file);
            if (value != null) return value;
        }
        return null;
    }

    private static String readGuestProcessName(java.io.File file) {
        if (file == null || !file.isFile()) return null;
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[512];
            int read = input.read(buffer);
            if (read <= 0) return null;
            String value = new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception ignored) {
            return null;
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
