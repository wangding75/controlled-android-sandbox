package com.warden.controlledsandbox.framework.identity;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
import java.util.concurrent.atomic.AtomicLong;

/** Guest-visible state for system services that must not expose host-owned data. */
public final class VirtualSystemServiceState implements AutoCloseable {
    private final VirtualSystemServiceAuthority authority;
    private final ClipboardState clipboard;
    private final AccountState accounts;
    private final AlarmState alarms;
    private final IntNamespace notifications;
    private final IntNamespace jobs;

    public VirtualSystemServiceState() { this(null); }

    public VirtualSystemServiceState(VirtualSystemServiceAuthority authority) {
        this.authority = authority;
        clipboard = new ClipboardState(authority);
        accounts = new AccountState(authority);
        alarms = new AlarmState(authority);
        notifications = new IntNamespace("notification", 0x51000000, authority);
        jobs = new IntNamespace("job", 0x52000000, authority);
    }

    public ClipboardState clipboard() { return clipboard; }
    public AccountState accounts() { return accounts; }
    public AlarmState alarms() { return alarms; }
    public IntNamespace notifications() { return notifications; }
    public IntNamespace jobs() { return jobs; }
    public boolean binderOwned() { return authority != null; }

    @Override public void close() {
        alarms.close();
        clipboard.close();
        if (authority == null) {
            accounts.clear(); notifications.clear(); jobs.clear();
        } else {
            try { authority.close(); } catch (Exception ignored) { }
        }
    }

    public static final class ClipboardState implements AutoCloseable {
        private final VirtualSystemServiceAuthority authority;
        private Object clip;
        private final Set<Object> listeners = Collections.newSetFromMap(new IdentityHashMap<>());

        ClipboardState(VirtualSystemServiceAuthority authority) {
            this.authority = authority;
            if (authority != null) authority.setClipboardChangeListener(this::dispatchListeners);
        }
        public synchronized void set(Object value) {
            if (authority == null) {
                clip = value;
                dispatchListeners();
            } else {
                authority.setClipboard(value);
            }
        }
        public synchronized Object get() { return authority == null ? clip : authority.clipboard(); }
        public synchronized boolean has() { return get() != null; }
        public synchronized void clear() {
            if (authority == null) {
                clip = null;
                dispatchListeners();
            } else {
                authority.clearClipboard();
            }
        }
        public synchronized void addListener(Object listener) { if (listener != null) listeners.add(listener); }
        public synchronized void removeListener(Object listener) { if (listener != null) listeners.remove(listener); }
        public synchronized int listenerCount() { return listeners.size(); }
        @Override public synchronized void close() { listeners.clear(); clip = null; }
        private synchronized void dispatchListeners() {
            for (Object listener : new ArrayList<>(listeners)) {
                invokeNoArg(listener, "dispatchPrimaryClipChanged", "onPrimaryClipChanged");
            }
        }
    }

    public static final class AccountState {
        private final VirtualSystemServiceAuthority authority;
        private final Map<AccountKey, AccountEntry> entries = new LinkedHashMap<>();
        AccountState(VirtualSystemServiceAuthority authority) { this.authority = authority; }

        public synchronized boolean add(Object account, String password) {
            AccountKey key = AccountKey.from(account);
            if (authority != null) return authority.addAccount(key.name, key.type, safe(password));
            if (entries.containsKey(key)) return false;
            entries.put(key, new AccountEntry(account, safe(password))); return true;
        }
        public synchronized boolean remove(Object account) {
            AccountKey key = AccountKey.from(account);
            return authority != null ? authority.removeAccount(key.name, key.type) : entries.remove(key) != null;
        }
        public synchronized void setPassword(Object account, String password) {
            AccountKey key = AccountKey.from(account);
            if (authority != null) authority.setPassword(key.name, key.type, safe(password));
            else require(account).password = safe(password);
        }
        public synchronized String password(Object account) {
            AccountKey key = AccountKey.from(account);
            if (authority != null) return authority.password(key.name, key.type);
            AccountEntry entry = entries.get(key); return entry == null ? null : entry.password;
        }
        public synchronized void setToken(Object account, String type, String token) {
            AccountKey key = AccountKey.from(account);
            if (authority != null) authority.setToken(key.name, key.type, normalize(type), safe(token));
            else require(account).tokens.put(normalize(type), safe(token));
        }
        public synchronized String token(Object account, String type) {
            AccountKey key = AccountKey.from(account);
            if (authority != null) return authority.token(key.name, key.type, normalize(type));
            AccountEntry entry = entries.get(key); return entry == null ? null : entry.tokens.get(normalize(type));
        }
        public synchronized void invalidateToken(String accountType, String token) {
            if (authority != null) { authority.invalidateToken(normalize(accountType), token); return; }
            String normalizedType = normalize(accountType);
            for (Map.Entry<AccountKey, AccountEntry> item : entries.entrySet()) {
                if (!normalizedType.isEmpty() && !normalizedType.equals(item.getKey().type)) continue;
                item.getValue().tokens.values().removeIf(value -> java.util.Objects.equals(value, token));
            }
        }
        public synchronized Object array(Class<?> componentType, String requestedType) {
            List<Object> values = new ArrayList<>();
            if (authority != null) {
                for (VirtualSystemServiceAuthority.AccountRecord record : authority.accounts(normalize(requestedType))) {
                    values.add(newAccount(componentType, record.name(), record.type()));
                }
            } else {
                String type = normalize(requestedType);
                for (Map.Entry<AccountKey, AccountEntry> item : entries.entrySet()) {
                    if (type.isEmpty() || type.equals(item.getKey().type)) values.add(item.getValue().account);
                }
            }
            Object array = Array.newInstance(componentType, values.size());
            for (int index = 0; index < values.size(); index++) Array.set(array, index, values.get(index));
            return array;
        }
        public synchronized int size() { return authority == null ? entries.size() : authority.accounts("").size(); }
        public synchronized void clear() { if (authority == null) entries.clear(); }
        private AccountEntry require(Object account) {
            AccountEntry entry = entries.get(AccountKey.from(account));
            if (entry == null) throw new IllegalArgumentException("VIRTUAL_ACCOUNT_NOT_FOUND");
            return entry;
        }
        private static Object newAccount(Class<?> type, String name, String accountType) {
            try {
                Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class);
                constructor.setAccessible(true); return constructor.newInstance(name, accountType);
            } catch (Throwable error) {
                throw new IllegalStateException("VIRTUAL_ACCOUNT_RECONSTRUCTION_UNSUPPORTED:" + type.getName(), error);
            }
        }
    }

    public static final class AlarmState implements AutoCloseable {
        private final VirtualSystemServiceAuthority authority;
        private final ScheduledExecutorService executor;
        private final ConcurrentMap<Object, AlarmEntry> alarms = new ConcurrentHashMap<>();
        private final AtomicLong remoteIds = new AtomicLong(1L);

        AlarmState(VirtualSystemServiceAuthority authority) {
            this.authority = authority;
            this.executor = authority == null ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sandbox-virtual-alarm"); thread.setDaemon(true); return thread;
            }) : null;
            if (authority != null) {
                long nextId = 1L;
                for (VirtualSystemServiceAuthority.AlarmRecord record : authority.alarms()) {
                    Object token = record.token();
                    if (token == null) continue;
                    AlarmEntry entry = new AlarmEntry(record.alarmId(), token, record.triggerAtMs(), record.intervalMs());
                    alarms.put(token, entry);
                    if (record.alarmId().startsWith("a")) {
                        try { nextId = Math.max(nextId, Long.parseLong(record.alarmId().substring(1)) + 1L); }
                        catch (NumberFormatException ignored) { }
                    }
                    authority.scheduleAlarm(record.alarmId(), record.triggerAtMs(), record.intervalMs(), token,
                            () -> dispatch(token));
                }
                remoteIds.set(nextId);
            }
        }
        public void schedule(Object token, long triggerAtMs, long intervalMs) {
            if (token == null) throw new IllegalArgumentException("VIRTUAL_ALARM_TOKEN_REQUIRED");
            cancel(token);
            if (authority != null) {
                String id = "a" + remoteIds.getAndIncrement();
                AlarmEntry entry = new AlarmEntry(id, token, triggerAtMs, Math.max(0L, intervalMs));
                alarms.put(token, entry);
                authority.scheduleAlarm(id, triggerAtMs, entry.intervalMs, token, () -> {
                    try { dispatch(token); }
                    finally { if (entry.intervalMs == 0L) alarms.remove(token, entry); }
                });
                return;
            }
            long delay = Math.max(0L, triggerAtMs - System.currentTimeMillis());
            AlarmEntry entry = new AlarmEntry("", token, triggerAtMs, Math.max(0L, intervalMs));
            Runnable delivery = () -> {
                try { dispatch(token); }
                finally { if (entry.intervalMs == 0L) alarms.remove(token, entry); }
            };
            ScheduledFuture<?> future = entry.intervalMs > 0L
                    ? executor.scheduleAtFixedRate(delivery, delay, entry.intervalMs, TimeUnit.MILLISECONDS)
                    : executor.schedule(delivery, delay, TimeUnit.MILLISECONDS);
            entry.future = future; alarms.put(token, entry);
        }
        public boolean cancel(Object token) {
            AlarmEntry removed = token == null ? null : alarms.remove(token);
            if (removed == null) return false;
            if (authority != null) return authority.cancelAlarm(removed.id);
            if (removed.future != null) removed.future.cancel(false); return true;
        }
        public int size() { return authority == null ? alarms.size() : authority.alarms().size(); }
        public List<Long> triggerTimes() {
            List<Long> times = new ArrayList<>();
            if (authority != null) for (VirtualSystemServiceAuthority.AlarmRecord record : authority.alarms()) times.add(record.triggerAtMs());
            else for (AlarmEntry entry : alarms.values()) times.add(entry.triggerAtMs);
            Collections.sort(times); return Collections.unmodifiableList(times);
        }
        @Override public void close() {
            if (authority == null) {
                for (AlarmEntry entry : alarms.values()) if (entry.future != null) entry.future.cancel(false);
                executor.shutdownNow();
            }
            alarms.clear();
        }
        private static void dispatch(Object token) {
            if (invokeNoArg(token, "send", "doAlarm", "onAlarm", "run")) return;
            throw new IllegalStateException("VIRTUAL_ALARM_TARGET_UNDELIVERABLE:" + token.getClass().getName());
        }
    }

    public static final class IntNamespace {
        public record Mapping(int hostId, boolean created) { }
        private final String namespace;
        private final VirtualSystemServiceAuthority authority;
        private final AtomicInteger next;
        private final Map<Integer, Integer> guestToHost = new LinkedHashMap<>();
        private final Map<Integer, Integer> hostToGuest = new LinkedHashMap<>();
        IntNamespace(String namespace, int seed, VirtualSystemServiceAuthority authority) {
            this.namespace = namespace; this.authority = authority; next = new AtomicInteger(seed);
        }
        public synchronized Mapping ensure(int guestId) {
            if (authority != null) {
                VirtualSystemServiceAuthority.NamespaceMapping mapping = authority.ensureNamespace(namespace, guestId);
                return new Mapping(mapping.hostId(), mapping.created());
            }
            Integer existing = guestToHost.get(guestId);
            if (existing != null) return new Mapping(existing, false);
            int candidate; do { candidate = next.getAndIncrement(); } while (hostToGuest.containsKey(candidate));
            guestToHost.put(guestId, candidate); hostToGuest.put(candidate, guestId); return new Mapping(candidate, true);
        }
        public synchronized int hostId(int guestId) { return ensure(guestId).hostId(); }
        public synchronized Integer hostIdIfPresent(int guestId) { return authority == null ? guestToHost.get(guestId) : authority.hostIdIfPresent(namespace, guestId); }
        public synchronized Integer guestId(int hostId) { return authority == null ? hostToGuest.get(hostId) : authority.guestId(namespace, hostId); }
        public synchronized Integer removeGuest(int guestId) {
            if (authority != null) return authority.removeNamespace(namespace, guestId);
            Integer host = guestToHost.remove(guestId); if (host != null) hostToGuest.remove(host); return host;
        }
        public synchronized List<Integer> guestIds() { return authority == null
                ? Collections.unmodifiableList(new ArrayList<>(guestToHost.keySet()))
                : Collections.unmodifiableList(new ArrayList<>(authority.guestIds(namespace))); }
        public synchronized int size() { return authority == null ? guestToHost.size() : authority.namespaceSize(namespace); }
        public synchronized void clear() { if (authority == null) { guestToHost.clear(); hostToGuest.clear(); } }
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
        final Object account; String password; final Map<String, String> tokens = new LinkedHashMap<>();
        AccountEntry(Object account, String password) { this.account = account; this.password = password; }
    }
    private static final class AlarmEntry {
        final String id; final Object token; final long triggerAtMs; final long intervalMs; volatile ScheduledFuture<?> future;
        AlarmEntry(String id, Object token, long triggerAtMs, long intervalMs) {
            this.id = id; this.token = token; this.triggerAtMs = triggerAtMs; this.intervalMs = intervalMs;
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
            for (String name : names) try { return cursor.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
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
    private static String safe(String value) { return value == null ? "" : value; }
}
