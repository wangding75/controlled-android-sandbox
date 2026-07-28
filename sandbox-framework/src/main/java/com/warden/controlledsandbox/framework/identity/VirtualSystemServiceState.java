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
    private final NotificationState notifications;
    private final JobState jobs;

    public VirtualSystemServiceState() { this(null); }

    public VirtualSystemServiceState(VirtualSystemServiceAuthority authority) {
        this.authority = authority;
        clipboard = new ClipboardState(authority);
        accounts = new AccountState(authority);
        alarms = new AlarmState(authority);
        notifications = new NotificationState(authority);
        jobs = new JobState(authority);
    }

    public ClipboardState clipboard() { return clipboard; }
    public AccountState accounts() { return accounts; }
    public AlarmState alarms() { return alarms; }
    public NotificationState notifications() { return notifications; }
    public JobState jobs() { return jobs; }
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

    public static final class NotificationState {
        public record Mapping(int hostId, boolean created) { }
        private record Key(int guestId, String guestTag) { }
        private static final class Entry {
            final int guestId; final int hostId; final String guestTag; final String hostTag;
            String channelId; String state; Object payload; long updatedAtMs;
            Entry(int guestId, int hostId, String guestTag, String hostTag, String channelId,
                  String state, Object payload, long updatedAtMs) {
                this.guestId = guestId; this.hostId = hostId; this.guestTag = guestTag;
                this.hostTag = hostTag; this.channelId = channelId; this.state = state;
                this.payload = payload; this.updatedAtMs = updatedAtMs;
            }
        }
        private final VirtualSystemServiceAuthority authority;
        private final AtomicInteger next = new AtomicInteger(0x51000000);
        private final Map<Key, Entry> entries = new LinkedHashMap<>();
        private final Map<String, VirtualSystemServiceAuthority.NotificationChannelRecord> channels = new LinkedHashMap<>();
        NotificationState(VirtualSystemServiceAuthority authority) { this.authority = authority; }

        public synchronized VirtualSystemServiceAuthority.NotificationRecord reserve(int guestId, String tag, String channelId) {
            String guestTag = normalize(tag); String channel = normalize(channelId);
            if (authority != null) return authority.reserveNotification(guestId, guestTag, channel);
            Key key = new Key(guestId, guestTag); Entry current = entries.get(key);
            if (current == null) {
                int host = next.getAndIncrement();
                current = new Entry(guestId, host, guestTag, "cs:" + host + ":" + guestTag,
                        channel, "RESERVED", null, System.currentTimeMillis());
                entries.put(key, current);
            } else {
                current.channelId = channel; current.state = "RESERVED"; current.updatedAtMs = System.currentTimeMillis();
            }
            return record(current);
        }
        public synchronized void commit(int guestId, String tag, String channelId, Object payload) {
            String guestTag = normalize(tag); String channel = normalize(channelId);
            if (authority != null) { authority.commitNotification(guestId, guestTag, channel, payload); return; }
            Entry entry = entries.get(new Key(guestId, guestTag));
            if (entry == null) throw new IllegalStateException("VIRTUAL_NOTIFICATION_RESERVATION_REQUIRED");
            entry.channelId = channel; entry.payload = payload; entry.state = "ACTIVE";
            entry.updatedAtMs = System.currentTimeMillis();
        }
        public synchronized boolean remove(int guestId, String tag) {
            String guestTag = normalize(tag);
            return authority != null ? authority.removeNotification(guestId, guestTag)
                    : entries.remove(new Key(guestId, guestTag)) != null;
        }
        public synchronized List<VirtualSystemServiceAuthority.NotificationRecord> records() {
            if (authority != null) return Collections.unmodifiableList(new ArrayList<>(authority.notifications()));
            List<VirtualSystemServiceAuthority.NotificationRecord> out = new ArrayList<>();
            for (Entry entry : entries.values()) out.add(record(entry));
            return Collections.unmodifiableList(out);
        }
        public synchronized Mapping ensure(int guestId) {
            VirtualSystemServiceAuthority.NotificationRecord before = find(guestId, "");
            VirtualSystemServiceAuthority.NotificationRecord value = reserve(guestId, "", "");
            return new Mapping(value.hostId(), before == null);
        }
        public synchronized Integer hostIdIfPresent(int guestId) {
            VirtualSystemServiceAuthority.NotificationRecord value = find(guestId, "");
            return value == null ? null : value.hostId();
        }
        public synchronized Integer guestId(int hostId) {
            for (VirtualSystemServiceAuthority.NotificationRecord value : records()) if (value.hostId() == hostId) return value.guestId();
            return null;
        }
        public synchronized Integer removeGuest(int guestId) {
            VirtualSystemServiceAuthority.NotificationRecord value = find(guestId, "");
            if (value == null) return null; remove(guestId, ""); return value.hostId();
        }
        public synchronized List<Integer> guestIds() {
            java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
            for (VirtualSystemServiceAuthority.NotificationRecord value : records()) out.add(value.guestId());
            return Collections.unmodifiableList(new ArrayList<>(out));
        }
        public synchronized int size() { return records().size(); }
        public synchronized void upsertChannel(String kind, String id, String groupId, Object payload) {
            if (authority != null) { authority.upsertNotificationChannel(kind, id, groupId, payload); return; }
            channels.put(kind + "#" + id, new VirtualSystemServiceAuthority.NotificationChannelRecord(
                    kind, id, normalize(groupId), payload, System.currentTimeMillis()));
        }
        public synchronized boolean removeChannel(String kind, String id) {
            return authority != null ? authority.removeNotificationChannel(kind, id)
                    : channels.remove(kind + "#" + id) != null;
        }
        public synchronized List<VirtualSystemServiceAuthority.NotificationChannelRecord> channels() {
            return authority != null ? Collections.unmodifiableList(new ArrayList<>(authority.notificationChannels()))
                    : Collections.unmodifiableList(new ArrayList<>(channels.values()));
        }
        public synchronized void clear() { if (authority == null) { entries.clear(); channels.clear(); } }
        private VirtualSystemServiceAuthority.NotificationRecord find(int guestId, String tag) {
            String normalized = normalize(tag);
            for (VirtualSystemServiceAuthority.NotificationRecord value : records()) {
                if (value.guestId() == guestId && value.guestTag().equals(normalized)) return value;
            }
            return null;
        }
        private static VirtualSystemServiceAuthority.NotificationRecord record(Entry entry) {
            return new VirtualSystemServiceAuthority.NotificationRecord(entry.guestId, entry.hostId,
                    entry.guestTag, entry.hostTag, entry.channelId, entry.state, entry.payload, entry.updatedAtMs);
        }
    }

    public static final class JobState {
        public record Mapping(int hostId, boolean created) { }
        private static final class Entry {
            final int guestId; final int hostId; String state; Object payload; long updatedAtMs;
            Entry(int guestId, int hostId, String state, Object payload, long updatedAtMs) {
                this.guestId = guestId; this.hostId = hostId; this.state = state;
                this.payload = payload; this.updatedAtMs = updatedAtMs;
            }
        }
        private final VirtualSystemServiceAuthority authority;
        private final AtomicInteger next = new AtomicInteger(0x52000000);
        private final Map<Integer, Entry> entries = new LinkedHashMap<>();
        JobState(VirtualSystemServiceAuthority authority) { this.authority = authority; }
        public synchronized VirtualSystemServiceAuthority.JobRecord reserve(int guestId, Object payload) {
            if (authority != null) return authority.reserveJob(guestId, payload);
            Entry entry = entries.get(guestId);
            if (entry == null) {
                entry = new Entry(guestId, next.getAndIncrement(), "RESERVED", payload, System.currentTimeMillis());
                entries.put(guestId, entry);
            } else { entry.state = "RESERVED"; entry.payload = payload; entry.updatedAtMs = System.currentTimeMillis(); }
            return record(entry);
        }
        public synchronized void commit(int guestId) {
            if (authority != null) { authority.commitJob(guestId); return; }
            Entry entry = entries.get(guestId);
            if (entry == null) throw new IllegalStateException("VIRTUAL_JOB_RESERVATION_REQUIRED");
            entry.state = "SCHEDULED"; entry.updatedAtMs = System.currentTimeMillis();
        }
        public synchronized boolean remove(int guestId) {
            return authority != null ? authority.removeJob(guestId) : entries.remove(guestId) != null;
        }
        public synchronized List<VirtualSystemServiceAuthority.JobRecord> records() {
            if (authority != null) return Collections.unmodifiableList(new ArrayList<>(authority.jobs()));
            List<VirtualSystemServiceAuthority.JobRecord> out = new ArrayList<>();
            for (Entry entry : entries.values()) out.add(record(entry));
            return Collections.unmodifiableList(out);
        }
        public synchronized Mapping ensure(int guestId) {
            VirtualSystemServiceAuthority.JobRecord before = findGuest(guestId);
            VirtualSystemServiceAuthority.JobRecord value = reserve(guestId, before == null ? null : before.payload());
            return new Mapping(value.hostId(), before == null);
        }
        public synchronized Integer hostIdIfPresent(int guestId) {
            VirtualSystemServiceAuthority.JobRecord value = findGuest(guestId); return value == null ? null : value.hostId();
        }
        public synchronized Integer guestId(int hostId) {
            for (VirtualSystemServiceAuthority.JobRecord value : records()) if (value.hostId() == hostId) return value.guestId();
            return null;
        }
        public synchronized Integer removeGuest(int guestId) {
            VirtualSystemServiceAuthority.JobRecord value = findGuest(guestId);
            if (value == null) return null; remove(guestId); return value.hostId();
        }
        public synchronized List<Integer> guestIds() {
            List<Integer> out = new ArrayList<>();
            for (VirtualSystemServiceAuthority.JobRecord value : records()) out.add(value.guestId());
            return Collections.unmodifiableList(out);
        }
        public synchronized int size() { return records().size(); }
        public synchronized void setExecutionListener(VirtualSystemServiceAuthority.JobExecutionListener listener) {
            if (authority != null) authority.setJobExecutionListener(listener);
        }
        public synchronized void clear() { if (authority == null) entries.clear(); }
        private VirtualSystemServiceAuthority.JobRecord findGuest(int guestId) {
            for (VirtualSystemServiceAuthority.JobRecord value : records()) if (value.guestId() == guestId) return value;
            return null;
        }
        private static VirtualSystemServiceAuthority.JobRecord record(Entry entry) {
            return new VirtualSystemServiceAuthority.JobRecord(entry.guestId, entry.hostId, entry.state,
                    "local", 0L, entry.payload, entry.updatedAtMs);
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
