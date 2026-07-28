package com.warden.controlledsandbox.framework.identity;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Generation-scoped state for system services that must not expose host-owned data. */
public final class VirtualSystemServiceState implements AutoCloseable {
    private final ClipboardState clipboard = new ClipboardState();
    private final AccountState accounts = new AccountState();
    private final AlarmState alarms = new AlarmState();
    private final IntNamespace notifications = new IntNamespace(0x51000000);
    private final IntNamespace jobs = new IntNamespace(0x52000000);

    public ClipboardState clipboard() { return clipboard; }
    public AccountState accounts() { return accounts; }
    public AlarmState alarms() { return alarms; }
    public IntNamespace notifications() { return notifications; }
    public IntNamespace jobs() { return jobs; }

    @Override public void close() {
        alarms.close();
        clipboard.clear();
        accounts.clear();
        notifications.clear();
        jobs.clear();
    }

    /** Process-local Clipboard state; listeners never reach the host Clipboard service. */
    public static final class ClipboardState {
        private Object clip;
        private final Set<Object> listeners = Collections.newSetFromMap(new IdentityHashMap<>());

        public synchronized void set(Object value) {
            clip = value;
            dispatchListeners();
        }
        public synchronized Object get() { return clip; }
        public synchronized boolean has() { return clip != null; }
        public synchronized void clear() { clip = null; dispatchListeners(); }
        public synchronized void addListener(Object listener) { if (listener != null) listeners.add(listener); }
        public synchronized void removeListener(Object listener) { if (listener != null) listeners.remove(listener); }
        public synchronized int listenerCount() { return listeners.size(); }

        private void dispatchListeners() {
            for (Object listener : new ArrayList<>(listeners)) {
                invokeNoArg(listener, "dispatchPrimaryClipChanged", "onPrimaryClipChanged");
            }
        }
    }

    /** Minimal per-Guest AccountManager storage. Authenticator and OAuth UI remain unsupported. */
    public static final class AccountState {
        private final Map<AccountKey, AccountEntry> entries = new LinkedHashMap<>();

        public synchronized boolean add(Object account, String password) {
            AccountKey key = AccountKey.from(account);
            if (entries.containsKey(key)) return false;
            entries.put(key, new AccountEntry(account, password == null ? "" : password));
            return true;
        }
        public synchronized boolean remove(Object account) { return entries.remove(AccountKey.from(account)) != null; }
        public synchronized void setPassword(Object account, String password) {
            AccountEntry entry = require(account); entry.password = password == null ? "" : password;
        }
        public synchronized String password(Object account) {
            AccountEntry entry = entries.get(AccountKey.from(account)); return entry == null ? null : entry.password;
        }
        public synchronized void setToken(Object account, String type, String token) {
            require(account).tokens.put(normalize(type), token == null ? "" : token);
        }
        public synchronized String token(Object account, String type) {
            AccountEntry entry = entries.get(AccountKey.from(account));
            return entry == null ? null : entry.tokens.get(normalize(type));
        }
        public synchronized void invalidateToken(String accountType, String token) {
            String normalizedType = normalize(accountType);
            for (Map.Entry<AccountKey, AccountEntry> item : entries.entrySet()) {
                if (!normalizedType.isEmpty() && !normalizedType.equals(item.getKey().type)) continue;
                item.getValue().tokens.values().removeIf(value -> java.util.Objects.equals(value, token));
            }
        }
        public synchronized Object array(Class<?> componentType, String requestedType) {
            String type = normalize(requestedType);
            List<Object> values = new ArrayList<>();
            for (Map.Entry<AccountKey, AccountEntry> item : entries.entrySet()) {
                if (type.isEmpty() || type.equals(item.getKey().type)) values.add(item.getValue().account);
            }
            Object array = Array.newInstance(componentType, values.size());
            for (int index = 0; index < values.size(); index++) Array.set(array, index, values.get(index));
            return array;
        }
        public synchronized int size() { return entries.size(); }
        public synchronized void clear() { entries.clear(); }

        private AccountEntry require(Object account) {
            AccountEntry entry = entries.get(AccountKey.from(account));
            if (entry == null) throw new IllegalArgumentException("VIRTUAL_ACCOUNT_NOT_FOUND");
            return entry;
        }
    }

    /** In-process alarm lifecycle. This deliberately does not leak alarms into the host namespace. */
    public static final class AlarmState implements AutoCloseable {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sandbox-virtual-alarm");
            thread.setDaemon(true);
            return thread;
        });
        private final ConcurrentMap<Object, AlarmEntry> alarms = new ConcurrentHashMap<>();

        public void schedule(Object token, long triggerAtMs, long intervalMs) {
            if (token == null) throw new IllegalArgumentException("VIRTUAL_ALARM_TOKEN_REQUIRED");
            cancel(token);
            long now = System.currentTimeMillis();
            long delay = Math.max(0L, triggerAtMs - now);
            AlarmEntry entry = new AlarmEntry(token, triggerAtMs, Math.max(0L, intervalMs));
            Runnable delivery = () -> {
                try { dispatch(token); }
                finally { if (entry.intervalMs == 0L) alarms.remove(token, entry); }
            };
            ScheduledFuture<?> future = entry.intervalMs > 0L
                    ? executor.scheduleAtFixedRate(delivery, delay, entry.intervalMs, TimeUnit.MILLISECONDS)
                    : executor.schedule(delivery, delay, TimeUnit.MILLISECONDS);
            entry.future = future;
            alarms.put(token, entry);
        }
        public boolean cancel(Object token) {
            AlarmEntry removed = token == null ? null : alarms.remove(token);
            if (removed == null) return false;
            ScheduledFuture<?> future = removed.future;
            if (future != null) future.cancel(false);
            return true;
        }
        public int size() { return alarms.size(); }
        public List<Long> triggerTimes() {
            List<Long> times = new ArrayList<>();
            for (AlarmEntry entry : alarms.values()) times.add(entry.triggerAtMs);
            Collections.sort(times); return Collections.unmodifiableList(times);
        }
        @Override public void close() {
            for (AlarmEntry entry : alarms.values()) {
                ScheduledFuture<?> future = entry.future; if (future != null) future.cancel(false);
            }
            alarms.clear(); executor.shutdownNow();
        }

        private static void dispatch(Object token) {
            if (invokeNoArg(token, "send", "doAlarm", "onAlarm", "run")) return;
            throw new IllegalStateException("VIRTUAL_ALARM_TARGET_UNDELIVERABLE:" + token.getClass().getName());
        }
    }

    /** Stable generation-local mapping between Guest IDs and host-facing namespaced IDs. */
    public static final class IntNamespace {
        public record Mapping(int hostId, boolean created) { }
        private final AtomicInteger next;
        private final Map<Integer, Integer> guestToHost = new LinkedHashMap<>();
        private final Map<Integer, Integer> hostToGuest = new LinkedHashMap<>();

        IntNamespace(int seed) { next = new AtomicInteger(seed); }
        public synchronized Mapping ensure(int guestId) {
            Integer existing = guestToHost.get(guestId);
            if (existing != null) return new Mapping(existing, false);
            int candidate;
            do { candidate = next.getAndIncrement(); } while (hostToGuest.containsKey(candidate));
            guestToHost.put(guestId, candidate);
            hostToGuest.put(candidate, guestId);
            return new Mapping(candidate, true);
        }
        public synchronized int hostId(int guestId) { return ensure(guestId).hostId(); }
        public synchronized Integer hostIdIfPresent(int guestId) { return guestToHost.get(guestId); }
        public synchronized Integer guestId(int hostId) { return hostToGuest.get(hostId); }
        public synchronized Integer removeGuest(int guestId) {
            Integer host = guestToHost.remove(guestId); if (host != null) hostToGuest.remove(host); return host;
        }
        public synchronized java.util.List<Integer> guestIds() {
            return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(guestToHost.keySet()));
        }
        public synchronized int size() { return guestToHost.size(); }
        public synchronized void clear() { guestToHost.clear(); hostToGuest.clear(); }
    }

    private record AccountKey(String name, String type) {
        static AccountKey from(Object account) {
            if (account == null) throw new IllegalArgumentException("account is required");
            String name = stringMember(account, "name", "mName", "getName");
            String type = stringMember(account, "type", "mType", "getType");
            if (name.isEmpty() || type.isEmpty()) throw new IllegalArgumentException("VIRTUAL_ACCOUNT_IDENTITY_INVALID");
            return new AccountKey(name, type);
        }
    }
    private static final class AccountEntry {
        final Object account;
        String password;
        final Map<String, String> tokens = new LinkedHashMap<>();
        AccountEntry(Object account, String password) { this.account = account; this.password = password; }
    }
    private static final class AlarmEntry {
        final Object token; final long triggerAtMs; final long intervalMs; volatile ScheduledFuture<?> future;
        AlarmEntry(Object token, long triggerAtMs, long intervalMs) {
            this.token = token; this.triggerAtMs = triggerAtMs; this.intervalMs = intervalMs;
        }
    }

    public static String stringMember(Object value, String field, String alternateField, String method) {
        for (String name : new String[]{field, alternateField}) {
            try { Field found = findField(value.getClass(), name); found.setAccessible(true);
                Object result = found.get(value); if (result != null) return String.valueOf(result); }
            catch (Throwable ignored) { }
        }
        try { Method found = value.getClass().getMethod(method); found.setAccessible(true);
            Object result = found.invoke(value); return result == null ? "" : String.valueOf(result); }
        catch (Throwable ignored) { return ""; }
    }
    public static Field findField(Class<?> type, String... names) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            for (String name : names) {
                try { return cursor.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
            }
            cursor = cursor.getSuperclass();
        }
        throw new NoSuchFieldException(type.getName());
    }
    static boolean invokeNoArg(Object target, String... names) {
        if (target == null) return false;
        for (String name : names) {
            try { Method method = target.getClass().getMethod(name); method.setAccessible(true); method.invoke(target); return true; }
            catch (Throwable ignored) { }
        }
        return false;
    }
    private static String normalize(String value) { return value == null ? "" : value.trim(); }
}
