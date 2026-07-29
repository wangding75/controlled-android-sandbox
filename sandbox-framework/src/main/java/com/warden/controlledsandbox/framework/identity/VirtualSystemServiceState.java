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
    private final PendingIntentState pendingIntents;
    private final AlarmState alarms;
    private final NotificationState notifications;
    private final JobState jobs;

    public VirtualSystemServiceState() { this(null); }

    public VirtualSystemServiceState(VirtualSystemServiceAuthority authority) {
        this.authority = authority;
        clipboard = new ClipboardState(authority);
        accounts = new AccountState(authority);
        pendingIntents = new PendingIntentState(authority);
        alarms = new AlarmState(authority);
        notifications = new NotificationState(authority);
        jobs = new JobState(authority);
    }

    public ClipboardState clipboard() { return clipboard; }
    public AccountState accounts() { return accounts; }
    public PendingIntentState pendingIntents() { return pendingIntents; }
    public AlarmState alarms() { return alarms; }
    public NotificationState notifications() { return notifications; }
    public JobState jobs() { return jobs; }
    public boolean binderOwned() { return authority != null; }

    @Override public void close() {
        alarms.close();
        clipboard.close();
        if (authority == null) {
            accounts.clear(); pendingIntents.clear(); notifications.clear(); jobs.clear();
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


    /** Package-Service-backed durable PendingIntent identity namespace. */
    public static final class PendingIntentState {
        private final VirtualSystemServiceAuthority authority;
        private final Map<String, VirtualSystemServiceAuthority.PendingIntentRecord> local = new LinkedHashMap<>();
        private long nextToken = 1L;
        PendingIntentState(VirtualSystemServiceAuthority authority) { this.authority = authority; }
        public synchronized VirtualSystemServiceAuthority.PendingIntentRecord reserve(
                VirtualSystemServiceAuthority.PendingIntentRecord candidate,
                boolean noCreate, boolean cancelCurrent, boolean updateCurrent) {
            if (authority != null) return authority.reservePendingIntent(candidate, noCreate, cancelCurrent, updateCurrent);
            local.values().removeIf(value -> !value.packageRevision().equals(candidate.packageRevision()));
            VirtualSystemServiceAuthority.PendingIntentRecord existing = null;
            for (VirtualSystemServiceAuthority.PendingIntentRecord value : local.values()) {
                if (sameIdentity(value, candidate) && !value.cancelled()) { existing = value; break; }
            }
            if (noCreate) return existing;
            if (existing != null && cancelCurrent) { local.remove(existing.tokenId()); existing = null; }
            if (existing != null) {
                if (updateCurrent) local.put(existing.tokenId(), candidateWithToken(candidate, existing.tokenId(), existing.sends()));
                return local.get(existing.tokenId());
            }
            String token = candidate.tokenId().isEmpty() ? "local-pi-" + (nextToken++) : candidate.tokenId();
            VirtualSystemServiceAuthority.PendingIntentRecord created = candidateWithToken(candidate, token, 0);
            local.put(token, created); return created;
        }
        public synchronized VirtualSystemServiceAuthority.PendingIntentRecord markSent(String tokenId) {
            if (authority != null) return authority.markPendingIntentSent(tokenId);
            VirtualSystemServiceAuthority.PendingIntentRecord value = local.get(tokenId);
            if (value == null) throw new IllegalStateException("VIRTUAL_PENDING_INTENT_CANCELLED");
            VirtualSystemServiceAuthority.PendingIntentRecord updated = new VirtualSystemServiceAuthority.PendingIntentRecord(
                    value.tokenId(), value.kind(), value.requestCode(), value.action(), value.component(), value.data(), value.filterIdentity(),
                    value.flags(), value.creatorPackage(), value.creatorUid(), value.requiredPermission(),
                    value.ownerProcessName(), value.ownerGeneration(), value.packageRevision(), value.payload(),
                    value.sends() + 1, (value.flags() & 0x40000000) != 0, System.currentTimeMillis());
            if (updated.cancelled()) local.remove(tokenId); else local.put(tokenId, updated);
            return updated;
        }
        public synchronized boolean cancel(String tokenId) {
            return authority != null ? authority.cancelPendingIntent(tokenId) : local.remove(tokenId) != null;
        }
        public synchronized List<VirtualSystemServiceAuthority.PendingIntentRecord> records() {
            return authority != null ? Collections.unmodifiableList(new ArrayList<>(authority.pendingIntents()))
                    : Collections.unmodifiableList(new ArrayList<>(local.values()));
        }
        public synchronized void clear() { if (authority == null) local.clear(); }
        private static boolean sameIdentity(VirtualSystemServiceAuthority.PendingIntentRecord a,
                VirtualSystemServiceAuthority.PendingIntentRecord b) {
            return a.kind().equals(b.kind()) && a.requestCode() == b.requestCode()
                    && a.filterIdentity().equals(b.filterIdentity());
        }
        private static VirtualSystemServiceAuthority.PendingIntentRecord candidateWithToken(
                VirtualSystemServiceAuthority.PendingIntentRecord value, String token, int sends) {
            return new VirtualSystemServiceAuthority.PendingIntentRecord(token, value.kind(), value.requestCode(),
                    value.action(), value.component(), value.data(), value.filterIdentity(), value.flags(), value.creatorPackage(),
                    value.creatorUid(), value.requiredPermission(), value.ownerProcessName(), value.ownerGeneration(),
                    value.packageRevision(), value.payload(), sends, false, System.currentTimeMillis());
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
                    AlarmEntry entry = new AlarmEntry(record.alarmId(), token, record.triggerAtMs(), record.intervalMs(),
                            record.exact(), record.allowWhileIdle(), record.deliveryPath(), record.pendingIntentTokenId(),
                            record.ownerProcessName(), record.ownerGeneration(), record.packageRevision());
                    alarms.put(token, entry);
                    if (record.alarmId().startsWith("a")) {
                        try { nextId = Math.max(nextId, Long.parseLong(record.alarmId().substring(1)) + 1L); }
                        catch (NumberFormatException ignored) { }
                    }
                    authority.scheduleAlarm(record, () -> dispatch(token));
                }
                remoteIds.set(nextId);
            }
        }
        public void schedule(Object token, long triggerAtMs, long intervalMs) {
            schedule(token, triggerAtMs, intervalMs, false, false, "LISTENER", "",
                    "legacy", 0L, "legacy-revision");
        }
        public void schedule(Object token, long triggerAtMs, long intervalMs, boolean exact,
                             boolean allowWhileIdle, String deliveryPath, String pendingIntentTokenId,
                             String ownerProcessName, long ownerGeneration, String packageRevision) {
            if (token == null) throw new IllegalArgumentException("VIRTUAL_ALARM_TOKEN_REQUIRED");
            cancel(token);
            String id = authority == null ? "" : "a" + remoteIds.getAndIncrement();
            AlarmEntry entry = new AlarmEntry(id, token, triggerAtMs, Math.max(0L, intervalMs), exact,
                    allowWhileIdle, deliveryPath, pendingIntentTokenId, ownerProcessName,
                    ownerGeneration, packageRevision);
            alarms.put(token, entry);
            if (authority != null) {
                VirtualSystemServiceAuthority.AlarmRecord record = new VirtualSystemServiceAuthority.AlarmRecord(
                        id, triggerAtMs, entry.intervalMs, exact, allowWhileIdle, deliveryPath,
                        pendingIntentTokenId, ownerProcessName, ownerGeneration, packageRevision,
                        token, 0, System.currentTimeMillis());
                authority.scheduleAlarm(record, () -> {
                    try { dispatch(token); }
                    finally { if (entry.intervalMs == 0L) alarms.remove(token, entry); }
                });
                return;
            }
            long delay = Math.max(0L, triggerAtMs - System.currentTimeMillis());
            Runnable delivery = () -> {
                try { dispatch(token); }
                finally { if (entry.intervalMs == 0L) alarms.remove(token, entry); }
            };
            ScheduledFuture<?> future = entry.intervalMs > 0L
                    ? executor.scheduleAtFixedRate(delivery, delay, entry.intervalMs, TimeUnit.MILLISECONDS)
                    : executor.schedule(delivery, delay, TimeUnit.MILLISECONDS);
            entry.future = future;
        }
        public boolean cancel(Object token) {
            AlarmEntry removed = token == null ? null : alarms.remove(token);
            if (removed == null) return false;
            if (authority != null) return authority.cancelAlarm(removed.id);
            if (removed.future != null) removed.future.cancel(false); return true;
        }
        public void setRecoveredDelivery(java.util.function.Function<VirtualSystemServiceAuthority.AlarmRecord, Boolean> delivery) {
            if (authority != null) authority.setRecoveredAlarmDelivery(delivery);
        }
        public int size() { return authority == null ? alarms.size() : authority.alarms().size(); }
        public List<Long> triggerTimes() {
            List<Long> times = new ArrayList<>();
            if (authority != null) for (VirtualSystemServiceAuthority.AlarmRecord record : authority.alarms()) times.add(record.triggerAtMs());
            else for (AlarmEntry entry : alarms.values()) times.add(entry.triggerAtMs);
            Collections.sort(times); return Collections.unmodifiableList(times);
        }
        public List<VirtualSystemServiceAuthority.AlarmRecord> records() {
            if (authority != null) return Collections.unmodifiableList(new ArrayList<>(authority.alarms()));
            List<VirtualSystemServiceAuthority.AlarmRecord> out = new ArrayList<>();
            for (AlarmEntry entry : alarms.values()) out.add(new VirtualSystemServiceAuthority.AlarmRecord(
                    entry.id, entry.triggerAtMs, entry.intervalMs, entry.exact, entry.allowWhileIdle,
                    entry.deliveryPath, entry.pendingIntentTokenId, entry.ownerProcessName,
                    entry.ownerGeneration, entry.packageRevision, entry.token, 0, 0L));
            return Collections.unmodifiableList(out);
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
            String channelId; String state; final String packageRevision;
            String contentIntentTokenId; String deleteIntentTokenId; List<String> actionIntentTokenIds;
            boolean foregroundService; String foregroundServiceKey;
            Object payload; long updatedAtMs;
            Entry(VirtualSystemServiceAuthority.NotificationRecord value) {
                this.guestId = value.guestId(); this.hostId = value.hostId(); this.guestTag = value.guestTag();
                this.hostTag = value.hostTag(); this.channelId = value.channelId(); this.state = value.state();
                this.packageRevision = value.packageRevision(); this.contentIntentTokenId = value.contentIntentTokenId();
                this.deleteIntentTokenId = value.deleteIntentTokenId();
                this.actionIntentTokenIds = immutableStrings(value.actionIntentTokenIds());
                this.foregroundService = value.foregroundService();
                this.foregroundServiceKey = value.foregroundServiceKey();
                this.payload = value.payload(); this.updatedAtMs = value.updatedAtMs();
            }
        }
        private final VirtualSystemServiceAuthority authority;
        private final AtomicInteger next = new AtomicInteger(0x51000000);
        private final Map<Key, Entry> entries = new LinkedHashMap<>();
        private final Map<String, VirtualSystemServiceAuthority.NotificationChannelRecord> channels = new LinkedHashMap<>();
        NotificationState(VirtualSystemServiceAuthority authority) { this.authority = authority; }

        public synchronized VirtualSystemServiceAuthority.NotificationRecord reserve(int guestId, String tag, String channelId) {
            return reserve(new VirtualSystemServiceAuthority.NotificationRecord(guestId, 0, normalize(tag), "",
                    normalize(channelId), "RESERVED", "legacy-revision", "", "", List.of(),
                    false, "", null, System.currentTimeMillis()));
        }
        public synchronized VirtualSystemServiceAuthority.NotificationRecord reserve(
                VirtualSystemServiceAuthority.NotificationRecord candidate) {
            if (authority != null) return authority.reserveNotification(candidate);
            Key key = new Key(candidate.guestId(), normalize(candidate.guestTag()));
            Entry current = entries.get(key);
            if (current == null) {
                int host = next.getAndIncrement();
                VirtualSystemServiceAuthority.NotificationRecord created = new VirtualSystemServiceAuthority.NotificationRecord(
                        candidate.guestId(), host, key.guestTag(), "cs:" + host + ":" + key.guestTag(),
                        normalize(candidate.channelId()), "RESERVED", candidate.packageRevision(),
                        candidate.contentIntentTokenId(), candidate.deleteIntentTokenId(), candidate.actionIntentTokenIds(),
                        candidate.foregroundService(), candidate.foregroundServiceKey(), null, System.currentTimeMillis());
                current = new Entry(created); entries.put(key, current);
            } else {
                current.channelId = normalize(candidate.channelId()); current.state = "RESERVED";
                current.contentIntentTokenId = normalize(candidate.contentIntentTokenId());
                current.deleteIntentTokenId = normalize(candidate.deleteIntentTokenId());
                current.actionIntentTokenIds = immutableStrings(candidate.actionIntentTokenIds());
                current.foregroundService = candidate.foregroundService();
                current.foregroundServiceKey = normalize(candidate.foregroundServiceKey());
                current.updatedAtMs = System.currentTimeMillis();
            }
            return record(current);
        }
        public synchronized void commit(int guestId, String tag, String channelId, Object payload) {
            VirtualSystemServiceAuthority.NotificationRecord current = find(guestId, tag);
            if (current == null) throw new IllegalStateException("VIRTUAL_NOTIFICATION_RESERVATION_REQUIRED");
            commit(new VirtualSystemServiceAuthority.NotificationRecord(current.guestId(), current.hostId(),
                    current.guestTag(), current.hostTag(), normalize(channelId), "ACTIVE", current.packageRevision(),
                    current.contentIntentTokenId(), current.deleteIntentTokenId(), current.actionIntentTokenIds(),
                    current.foregroundService(), current.foregroundServiceKey(), payload, System.currentTimeMillis()));
        }
        public synchronized void commit(VirtualSystemServiceAuthority.NotificationRecord value) {
            if (authority != null) { authority.commitNotification(value); return; }
            Entry entry = entries.get(new Key(value.guestId(), normalize(value.guestTag())));
            if (entry == null) throw new IllegalStateException("VIRTUAL_NOTIFICATION_RESERVATION_REQUIRED");
            entry.channelId = normalize(value.channelId()); entry.payload = value.payload(); entry.state = "ACTIVE";
            entry.contentIntentTokenId = normalize(value.contentIntentTokenId());
            entry.deleteIntentTokenId = normalize(value.deleteIntentTokenId());
            entry.actionIntentTokenIds = immutableStrings(value.actionIntentTokenIds());
            entry.foregroundService = value.foregroundService();
            entry.foregroundServiceKey = normalize(value.foregroundServiceKey());
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
            upsertChannel(new VirtualSystemServiceAuthority.NotificationChannelRecord(kind, id, normalize(groupId),
                    "legacy-revision", payload, System.currentTimeMillis()));
        }
        public synchronized void upsertChannel(VirtualSystemServiceAuthority.NotificationChannelRecord value) {
            if (authority != null) { authority.upsertNotificationChannel(value); return; }
            channels.put(value.kind() + "#" + value.id(), value);
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
                    entry.guestTag, entry.hostTag, entry.channelId, entry.state, entry.packageRevision,
                    entry.contentIntentTokenId, entry.deleteIntentTokenId, entry.actionIntentTokenIds,
                    entry.foregroundService, entry.foregroundServiceKey, entry.payload, entry.updatedAtMs);
        }
        private static List<String> immutableStrings(List<String> values) {
            return Collections.unmodifiableList(new ArrayList<>(values == null ? List.of() : values));
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
        final String id; final Object token; final long triggerAtMs; final long intervalMs;
        final boolean exact; final boolean allowWhileIdle; final String deliveryPath;
        final String pendingIntentTokenId; final String ownerProcessName; final long ownerGeneration;
        final String packageRevision; volatile ScheduledFuture<?> future;
        AlarmEntry(String id, Object token, long triggerAtMs, long intervalMs, boolean exact,
                   boolean allowWhileIdle, String deliveryPath, String pendingIntentTokenId,
                   String ownerProcessName, long ownerGeneration, String packageRevision) {
            this.id = id; this.token = token; this.triggerAtMs = triggerAtMs; this.intervalMs = intervalMs;
            this.exact = exact; this.allowWhileIdle = allowWhileIdle;
            this.deliveryPath = deliveryPath == null ? "LISTENER" : deliveryPath;
            this.pendingIntentTokenId = pendingIntentTokenId == null ? "" : pendingIntentTokenId;
            this.ownerProcessName = ownerProcessName == null ? "legacy" : ownerProcessName;
            this.ownerGeneration = ownerGeneration;
            this.packageRevision = packageRevision == null ? "legacy-revision" : packageRevision;
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
