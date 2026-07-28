package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Service-specific isolation and namespace rewriting for bounded framework surfaces. */
public final class VirtualSystemServiceInterceptor {
    private final GuestIdentity identity;
    private final String service;
    private final VirtualSystemServiceState state;

    public VirtualSystemServiceInterceptor(GuestIdentity identity, String service) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.service = normalize(service);
        this.state = identity.virtualServices();
    }

    public Call before(Method method, Object[] arguments) {
        if (method == null) return Call.passThrough();
        return switch (service) {
            case "clipboard" -> clipboard(method, arguments);
            case "account" -> account(method, arguments);
            case "alarm" -> alarm(method, arguments);
            case "notification" -> notification(method, arguments);
            case "jobscheduler" -> jobs(method, arguments);
            default -> Call.passThrough();
        };
    }

    private Call clipboard(Method method, Object[] arguments) {
        String name = normalize(method.getName());
        VirtualSystemServiceState.ClipboardState clipboard = state.clipboard();
        if (name.startsWith("setprimaryclip")) {
            clipboard.set(firstPayload(arguments));
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("getprimaryclipdescription")) {
            Object clip = clipboard.get();
            return Call.handled(clip == null ? null : invoke(clip, "getDescription"));
        }
        if (name.startsWith("getprimaryclipsource")) return Call.handled(identity.packageName());
        if (name.startsWith("getprimaryclip")) return Call.handled(clipboard.get());
        if (name.startsWith("hasprimaryclip") || name.startsWith("hasclipboardtext")) {
            return Call.handled(clipboard.has());
        }
        if (name.startsWith("clearprimaryclip")) {
            clipboard.clear();
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("addprimaryclipchangedlistener")) {
            clipboard.addListener(firstPayload(arguments));
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("removeprimaryclipchangedlistener")) {
            clipboard.removeListener(firstPayload(arguments));
            return Call.handled(defaultValue(method.getReturnType()));
        }
        throw new SecurityException("VIRTUAL_CLIPBOARD_SIGNATURE_UNSUPPORTED:" + method.getName());
    }

    private Call account(Method method, Object[] arguments) {
        String name = normalize(method.getName());
        VirtualSystemServiceState.AccountState accounts = state.accounts();
        if (name.startsWith("getaccounts") || name.startsWith("getaccountsbytype")) {
            Class<?> returnType = method.getReturnType();
            if (!returnType.isArray()) {
                throw new SecurityException("VIRTUAL_ACCOUNT_QUERY_SIGNATURE_UNSUPPORTED:" + method.getName());
            }
            String requestedType = firstAccountType(arguments);
            return Call.handled(accounts.array(returnType.getComponentType(), requestedType));
        }
        if (name.equals("addaccountexplicitly") || name.startsWith("addaccountexplicitlywithvisibility")) {
            Object account = firstAccount(arguments);
            String password = stringAfter(arguments, account);
            return Call.handled(accounts.add(account, password));
        }
        if (name.startsWith("removeaccountexplicitly") || name.startsWith("removeaccountasuser")) {
            return Call.handled(accounts.remove(firstAccount(arguments)));
        }
        if (name.startsWith("setpassword")) {
            Object account = firstAccount(arguments); accounts.setPassword(account, stringAfter(arguments, account));
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("clearpassword")) {
            accounts.setPassword(firstAccount(arguments), "");
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("getpassword")) return Call.handled(accounts.password(firstAccount(arguments)));
        if (name.startsWith("setauthtoken")) {
            Object account = firstAccount(arguments);
            List<String> strings = stringsAfter(arguments, account);
            accounts.setToken(account, strings.size() > 0 ? strings.get(0) : "",
                    strings.size() > 1 ? strings.get(1) : "");
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("peekauthtoken")) {
            Object account = firstAccount(arguments);
            return Call.handled(accounts.token(account, stringAfter(arguments, account)));
        }
        if (name.startsWith("invalidateauthtoken")) {
            String[] strings = stringArguments(arguments);
            accounts.invalidateToken(strings.length > 0 ? strings[0] : "", strings.length > 1 ? strings[1] : "");
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("accountauthenticated")) return Call.handled(accounts.password(firstAccount(arguments)) != null);
        if (name.startsWith("getaccountvisibility") || name.startsWith("setaccountvisibility")) {
            return Call.handled(method.getReturnType() == boolean.class ? Boolean.TRUE : Integer.valueOf(1));
        }
        if (name.startsWith("registeraccountlistener") || name.startsWith("unregisteraccountlistener")) {
            return Call.handled(defaultValue(method.getReturnType()));
        }
        throw new SecurityException("VIRTUAL_ACCOUNT_SIGNATURE_UNSUPPORTED:" + method.getName());
    }

    private Call alarm(Method method, Object[] arguments) {
        String name = normalize(method.getName());
        if (name.startsWith("set") || name.startsWith("schedule")) {
            Object token = alarmToken(arguments);
            long trigger = normalizedTrigger(arguments);
            long interval = repeating(name) ? interval(arguments, trigger) : 0L;
            state.alarms().schedule(token, trigger, interval);
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("remove") || name.startsWith("cancel")) {
            state.alarms().cancel(alarmToken(arguments));
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("canScheduleExactAlarms".toLowerCase(Locale.ROOT))) {
            return Call.handled(identity.permissionPolicy().isGranted("android.permission.SCHEDULE_EXACT_ALARM"));
        }
        if (name.startsWith("getnextalarmclock")) return Call.handled(null);
        if (name.contains("time") || name.contains("timezone")) {
            throw new SecurityException("VIRTUAL_ALARM_HOST_CLOCK_MUTATION_DENIED:" + method.getName());
        }
        throw new SecurityException("VIRTUAL_ALARM_SIGNATURE_UNSUPPORTED:" + method.getName());
    }

    private Call notification(Method method, Object[] arguments) {
        String name = normalize(method.getName());
        List<Restore> restores = new ArrayList<>();
        if (name.contains("cancelall")) {
            throw new SecurityException("VIRTUAL_NOTIFICATION_CANCEL_ALL_UNSUPPORTED");
        }
        if (name.contains("enqueue") || name.contains("notify")) {
            rewriteNotificationTag(arguments, restores);
            NamespaceRewrite rewrite = rewriteNotificationId(arguments, restores, true);
            rewriteChannelStrings(arguments, restores, name);
            rewriteNotificationChannelFields(arguments, restores);
            return Call.passThroughLifecycle(restores, result -> result,
                    () -> { if (rewrite.created) state.notifications().removeGuest(rewrite.guestId); });
        }
        if (name.contains("cancel")) {
            rewriteNotificationTag(arguments, restores);
            NamespaceRewrite rewrite = rewriteNotificationId(arguments, restores, false);
            if (!rewrite.present) return Call.handled(defaultValue(method.getReturnType()));
            return Call.passThroughLifecycle(restores, result -> {
                state.notifications().removeGuest(rewrite.guestId);
                return result;
            }, () -> { });
        }
        if (name.contains("channel") || name.contains("group")) {
            rewriteChannelStrings(arguments, restores, name);
            rewriteChannelObjects(arguments, restores);
            return Call.passThroughLifecycle(restores, this::restoreChannelResult, () -> { });
        }
        if (name.startsWith("arenotificationsenabled")) return Call.handled(Boolean.TRUE);
        return Call.passThrough();
    }

    private Call jobs(Method method, Object[] arguments) {
        String name = normalize(method.getName());
        List<Restore> restores = new ArrayList<>();
        if (name.startsWith("schedule") || name.startsWith("enqueue")) {
            Object job = firstObjectWithMethod(arguments, "getId");
            if (job == null) throw new SecurityException("VIRTUAL_JOB_OBJECT_REQUIRED");
            int virtualId = intResult(job, "getId");
            VirtualSystemServiceState.IntNamespace.Mapping mapping = state.jobs().ensure(virtualId);
            Field field = findField(job.getClass(), "jobId", "mJobId");
            if (field == null) throw new SecurityException("VIRTUAL_JOB_ID_FIELD_UNSUPPORTED");
            try {
                field.setAccessible(true); Object original = field.get(job); field.set(job, mapping.hostId());
                restores.add(() -> field.set(job, original));
            } catch (ReflectiveOperationException error) {
                if (mapping.created()) state.jobs().removeGuest(virtualId);
                throw new SecurityException("VIRTUAL_JOB_ID_REWRITE_FAILED", error);
            }
            return Call.passThroughLifecycle(restores, result -> result,
                    () -> { if (mapping.created()) state.jobs().removeGuest(virtualId); });
        }
        if (name.startsWith("cancelall")) {
            throw new SecurityException("VIRTUAL_JOB_CANCEL_ALL_UNSUPPORTED");
        }
        if (name.startsWith("cancel") || name.startsWith("getpendingjob")) {
            int index = firstIntIndex(arguments);
            if (index < 0) throw new SecurityException("VIRTUAL_JOB_ID_REQUIRED");
            int virtualId = ((Number) arguments[index]).intValue();
            Integer hostId = state.jobs().hostIdIfPresent(virtualId);
            if (hostId == null) return Call.handled(defaultValue(method.getReturnType()));
            Object original = arguments[index]; arguments[index] = hostId;
            restores.add(() -> arguments[index] = original);
            if (name.startsWith("cancel")) {
                return Call.passThroughLifecycle(restores, result -> {
                    state.jobs().removeGuest(virtualId);
                    return result;
                }, () -> { });
            }
            return Call.passThrough(restores);
        }
        if (name.startsWith("getallpendingjobs")) return Call.passThroughWithResult(this::rewriteJobResults);
        return Call.passThrough();
    }

    private Object rewriteJobResults(Object result) {
        if (!(result instanceof List<?> list)) return result;
        List<Object> filtered = new ArrayList<>();
        for (Object job : list) {
            try {
                int hostId = intResult(job, "getId");
                Integer guestId = state.jobs().guestId(hostId);
                if (guestId == null) continue;
                Field field = findField(job.getClass(), "jobId", "mJobId");
                if (field != null) { field.setAccessible(true); field.set(job, guestId); }
                filtered.add(job);
            } catch (Throwable ignored) { }
        }
        return filtered;
    }

    private NamespaceRewrite rewriteNotificationId(Object[] arguments, List<Restore> restores,
                                                   boolean create) {
        int index = notificationIdIndex(arguments);
        if (index < 0) return NamespaceRewrite.absent();
        int guestId = ((Number) arguments[index]).intValue();
        VirtualSystemServiceState.IntNamespace.Mapping mapping;
        if (create) mapping = state.notifications().ensure(guestId);
        else {
            Integer host = state.notifications().hostIdIfPresent(guestId);
            if (host == null) return new NamespaceRewrite(guestId, 0, false, false);
            mapping = new VirtualSystemServiceState.IntNamespace.Mapping(host, false);
        }
        Object original = arguments[index]; arguments[index] = mapping.hostId();
        restores.add(() -> arguments[index] = original);
        return new NamespaceRewrite(guestId, mapping.hostId(), true, mapping.created());
    }

    private void rewriteNotificationTag(Object[] arguments, List<Restore> restores) {
        if (arguments == null) return;
        for (int index = 0; index < arguments.length; index++) {
            Object value = arguments[index];
            if (!(value instanceof String string)) continue;
            if (string.equals(identity.packageName()) || string.equals(identity.hostPackageName())) continue;
            if (string.contains("permission") || string.startsWith("android:")) continue;
            String namespaced = "cs:u" + identity.virtualUserId() + ":g" + identity.generation() + ":" + string;
            arguments[index] = namespaced;
            int restoreIndex = index;
            restores.add(() -> arguments[restoreIndex] = value);
            return;
        }
    }

    private void rewriteNotificationChannelFields(Object[] arguments, List<Restore> restores) {
        if (arguments == null) return;
        for (Object value : arguments) {
            if (value == null || !value.getClass().getName().contains("Notification")) continue;
            rewriteStringField(value, restores, "mChannelId", "channelId");
            rewriteStringField(value, restores, "mShortcutId", "shortcutId");
        }
    }

    private void rewriteChannelObjects(Object[] arguments, List<Restore> restores) {
        if (arguments == null) return;
        java.util.Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object value : arguments) rewriteChannelObject(value, restores, visited, true);
    }

    private Object restoreChannelResult(Object result) {
        java.util.Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        rewriteChannelObject(result, new ArrayList<>(), visited, false);
        return result;
    }

    private void rewriteChannelObject(Object value, List<Restore> restores, java.util.Set<Object> visited,
                                      boolean toHost) {
        if (value == null || !visited.add(value)) return;
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) rewriteChannelObject(item, restores, visited, toHost);
            return;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) rewriteChannelObject(
                    java.lang.reflect.Array.get(value, index), restores, visited, toHost);
            return;
        }
        String className = value.getClass().getName();
        if (!className.contains("NotificationChannel") && !className.contains("NotificationChannelGroup")) return;
        rewriteStringField(value, restores, toHost, "mId", "id");
        if (className.contains("NotificationChannel") && !className.contains("Group")) {
            rewriteStringField(value, restores, toHost, "mGroup", "group");
        }
    }

    private void rewriteStringField(Object value, List<Restore> restores, String... names) {
        rewriteStringField(value, restores, true, names);
    }

    private void rewriteStringField(Object value, List<Restore> restores, boolean toHost, String... names) {
        Field field = findField(value.getClass(), names);
        if (field == null) return;
        try {
            field.setAccessible(true);
            Object raw = field.get(value);
            if (!(raw instanceof String string) || string.isEmpty()) return;
            String updated = toHost ? channelNamespace(string) : stripChannelNamespace(string);
            if (updated.equals(string)) return;
            field.set(value, updated);
            if (toHost) restores.add(() -> field.set(value, raw));
        } catch (ReflectiveOperationException error) {
            throw new SecurityException("VIRTUAL_NOTIFICATION_CHANNEL_FIELD_UNSUPPORTED", error);
        }
    }

    private String channelNamespace(String value) {
        String prefix = "cs.u" + identity.virtualUserId() + "." + identity.packageName() + ".";
        return value.startsWith(prefix) ? value : prefix + value;
    }
    private String stripChannelNamespace(String value) {
        String prefix = "cs.u" + identity.virtualUserId() + "." + identity.packageName() + ".";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private void rewriteChannelStrings(Object[] arguments, List<Restore> restores, String methodName) {
        if (arguments == null || !(methodName.contains("channel") || methodName.contains("group"))) return;
        for (int index = 0; index < arguments.length; index++) {
            Object value = arguments[index];
            if (!(value instanceof String string)) continue;
            if (string.equals(identity.packageName()) || string.equals(identity.hostPackageName())) continue;
            String namespaced = channelNamespace(string);
            arguments[index] = namespaced;
            int restoreIndex = index;
            restores.add(() -> arguments[restoreIndex] = value);
        }
    }

    private static int notificationIdIndex(Object[] arguments) {
        if (arguments == null) return -1;
        for (int index = 0; index < arguments.length - 1; index++) {
            if (arguments[index] instanceof Integer && arguments[index + 1] != null
                    && arguments[index + 1].getClass().getName().contains("Notification")) return index;
        }
        for (int index = 0; index < arguments.length; index++) if (arguments[index] instanceof Integer) return index;
        return -1;
    }

    private static Object alarmToken(Object[] arguments) {
        if (arguments == null) throw new IllegalArgumentException("VIRTUAL_ALARM_ARGUMENTS_REQUIRED");
        for (int index = arguments.length - 1; index >= 0; index--) {
            Object value = arguments[index];
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) continue;
            String type = value.getClass().getName();
            if (type.contains("WorkSource") || type.contains("AlarmClockInfo") || type.contains("Bundle")) continue;
            return value;
        }
        throw new IllegalArgumentException("VIRTUAL_ALARM_TOKEN_REQUIRED");
    }

    private static long normalizedTrigger(Object[] arguments) {
        long trigger = System.currentTimeMillis();
        if (arguments != null) for (Object value : arguments) {
            if (value instanceof Long && ((Long) value) > 0L) { trigger = (Long) value; break; }
        }
        int type = -1;
        if (arguments != null) for (Object value : arguments) {
            if (value instanceof Integer) { type = (Integer) value; break; }
        }
        if (type == 2 || type == 3) {
            long elapsedNow = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            return System.currentTimeMillis() + Math.max(0L, trigger - elapsedNow);
        }
        return trigger;
    }
    private static long interval(Object[] arguments, long trigger) {
        boolean seen = false;
        if (arguments != null) for (Object value : arguments) {
            if (!(value instanceof Long)) continue;
            long candidate = (Long) value;
            if (!seen && candidate == trigger) { seen = true; continue; }
            if (candidate > 0L) return candidate;
        }
        return 0L;
    }
    private static boolean repeating(String name) { return name.contains("repeat"); }

    private static Object firstPayload(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) {
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) continue;
            return value;
        }
        return null;
    }
    private static Object firstAccount(Object[] arguments) {
        if (arguments != null) for (Object value : arguments) {
            if (value == null) continue;
            String type = value.getClass().getName();
            if (type.endsWith(".Account") || type.endsWith("$Account")
                    || (!VirtualSystemServiceState.stringMember(value, "name", "mName", "getName").isEmpty()
                    && !VirtualSystemServiceState.stringMember(value, "type", "mType", "getType").isEmpty())) return value;
        }
        throw new IllegalArgumentException("VIRTUAL_ACCOUNT_REQUIRED");
    }
    private static String firstAccountType(Object[] arguments) {
        if (arguments == null) return "";
        for (Object value : arguments) if (value instanceof String string
                && !string.contains(".") && !string.contains(":")) return string;
        return "";
    }
    private static String stringAfter(Object[] arguments, Object anchor) {
        boolean found = false;
        if (arguments != null) for (Object value : arguments) {
            if (value == anchor) { found = true; continue; }
            if (found && value instanceof String) return (String) value;
        }
        return "";
    }
    private static List<String> stringsAfter(Object[] arguments, Object anchor) {
        boolean found = false; List<String> result = new ArrayList<>();
        if (arguments != null) for (Object value : arguments) {
            if (value == anchor) { found = true; continue; }
            if (found && value instanceof String) result.add((String) value);
        }
        return result;
    }
    private static String[] stringArguments(Object[] arguments) {
        List<String> result = new ArrayList<>();
        if (arguments != null) for (Object value : arguments) if (value instanceof String) result.add((String) value);
        return result.toArray(new String[0]);
    }
    private static Object firstObjectWithMethod(Object[] arguments, String method) {
        if (arguments != null) for (Object value : arguments) {
            if (value == null) continue;
            try { Method found = value.getClass().getMethod(method); found.setAccessible(true); return value; } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }
    private static int intResult(Object value, String method) {
        try { Method found = value.getClass().getMethod(method); found.setAccessible(true); Object result = found.invoke(value); return ((Number) result).intValue(); }
        catch (ReflectiveOperationException error) { throw new IllegalArgumentException("Cannot read integer method " + method, error); }
    }
    private static int firstIntIndex(Object[] arguments) {
        if (arguments != null) for (int index = 0; index < arguments.length; index++) if (arguments[index] instanceof Integer) return index;
        return -1;
    }
    private static Field findField(Class<?> type, String... names) {
        try { return VirtualSystemServiceState.findField(type, names); } catch (NoSuchFieldException error) { return null; }
    }
    private static Object invoke(Object target, String methodName) {
        if (target == null) return null;
        try { Method method = target.getClass().getMethod(methodName); method.setAccessible(true); return method.invoke(target); }
        catch (Throwable ignored) { return null; }
    }
    private static Object defaultValue(Class<?> type) {
        if (type == void.class) return null;
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }

    private record NamespaceRewrite(int guestId, int hostId, boolean present, boolean created) {
        static NamespaceRewrite absent() { return new NamespaceRewrite(0, 0, false, false); }
    }

    @FunctionalInterface private interface Restore { void restore() throws Exception; }
    @FunctionalInterface public interface ResultRewrite { Object rewrite(Object result); }

    public static final class Call implements AutoCloseable {
        private static final Call PASS = new Call(false, null, List.of(), result -> result, () -> { });
        private final boolean handled;
        private final Object result;
        private final List<Restore> restores;
        private final ResultRewrite resultRewrite;
        private final Runnable failureAction;

        private Call(boolean handled, Object result, List<Restore> restores,
                     ResultRewrite resultRewrite, Runnable failureAction) {
            this.handled = handled; this.result = result;
            this.restores = restores == null ? List.of() : List.copyOf(restores);
            this.resultRewrite = resultRewrite == null ? value -> value : resultRewrite;
            this.failureAction = failureAction == null ? () -> { } : failureAction;
        }
        public static Call passThrough() { return PASS; }
        static Call passThrough(List<Restore> restores) {
            return new Call(false, null, restores, result -> result, () -> { });
        }
        static Call passThroughWithResult(ResultRewrite rewrite) {
            return new Call(false, null, List.of(), rewrite, () -> { });
        }
        static Call passThroughLifecycle(List<Restore> restores, ResultRewrite rewrite, Runnable failure) {
            return new Call(false, null, restores, rewrite, failure);
        }
        static Call handled(Object value) {
            return new Call(true, value, List.of(), result -> result, () -> { });
        }
        public boolean handled() { return handled; }
        public Object result() { return result; }
        public Object rewriteResult(Object value) { return resultRewrite.rewrite(value); }
        public void onFailure() { failureAction.run(); }
        @Override public void close() {
            for (int index = restores.size() - 1; index >= 0; index--) {
                try { restores.get(index).restore(); } catch (Exception ignored) { }
            }
        }
    }

}
